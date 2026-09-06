package com.itmeansbigmountain.clanwarboard;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javax.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class ClanWarBoardApiClient
{
	static final String DEFAULT_SERVICE_URL = "https://salmon-dune-01c80c60f.7.azurestaticapps.net";
	private static final String USER_AGENT = "ClanWarBoard-RuneLite/1.0";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final String serviceUrl;

	@Inject
	ClanWarBoardApiClient(OkHttpClient httpClient, Gson gson)
	{
		this(httpClient, gson, DEFAULT_SERVICE_URL);
	}

	ClanWarBoardApiClient(OkHttpClient httpClient, Gson gson, String serviceUrl)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.serviceUrl = serviceUrl == null || serviceUrl.trim().isEmpty()
			? DEFAULT_SERVICE_URL
			: serviceUrl.replaceAll("/$", "");
	}

	ClanWarBoardState fetchBoardState(String clanName, int clanMemberCount, ClanWarBoardSession session) throws IOException
	{
		try
		{
			JsonObject health = gson.fromJson(get("/api/health"), JsonObject.class);
			if (health == null || !health.has("ok") || !health.get("ok").getAsBoolean())
			{
				throw new IOException("Clan War Board health check did not return ok");
			}
			String clans = get("/api/clans");
			String availability = get("/api/public/availability");
			JsonObject clanRoot = gson.fromJson(clans, JsonObject.class);
			if (clanRoot == null || !clanRoot.has("clans") || !clanRoot.get("clans").isJsonArray())
			{
				throw new IOException("Clan War Board clans response is malformed");
			}
			JsonArray clanRows = clanRoot.getAsJsonArray("clans");
			int installedMembers = 0;
			String normalizedClan = normalizeClanId(clanName);
			for (JsonElement element : clanRows)
			{
				JsonObject row = element.getAsJsonObject();
				if (normalizedClan.equals(string(row, "clan_id")))
				{
					installedMembers = integer(row, "member_count");
				}
			}
			List<WarBoardFight> open = parseFights(availability, "availability");
			List<WarBoardFight> scheduled = parseFights(availability, "scheduled");
			List<WarBoardFight> history = parseFights(availability, "history");
			PlayerWarMetrics metrics = PlayerWarMetrics.empty();
			ClanWarBoardApiStatus status = ClanWarBoardApiStatus.online("Connected to Clan War Board", clanRows.size(), open.size());
			return new ClanWarBoardState(status, installedMembers, clanMemberCount, open, scheduled, history, metrics);
		}
		catch (IOException ex)
		{
			throw ex;
		}
		catch (RuntimeException ex)
		{
			throw new IOException("Clan War Board returned malformed JSON", ex);
		}
	}

	private PlayerWarMetrics parsePlayerMetrics(String json)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
		JsonObject metrics = root == null || !root.has("metrics") ? new JsonObject() : root.getAsJsonObject("metrics");
		return new PlayerWarMetrics(integer(metrics, "fightsObserved"), integer(metrics, "observedKills"),
			integer(metrics, "deaths"), integer(metrics, "returns"), integer(metrics, "opponentDamage"),
			integer(metrics, "friendlyFireDamage"), integer(metrics, "damageInflicted"),
			integer(metrics, "damageReceived"), integer(metrics, "thirdPartyDamage"),
			integer(metrics, "activitySamples"), integer(metrics, "eventsTracked"));
	}

	ClanWarBoardSession register(String installId, ClanAccess access, String pluginVersion) throws IOException
	{
		if (access == null || access.getClanName() == null || access.getClanName().trim().isEmpty())
		{
			throw new IOException("Clan membership is required before registration");
		}
		return parseSessionResponse(post("/api/plugin/register", registrationJson(installId, access, pluginVersion), Collections.emptyMap()));
	}

	ClanWarBoardSession rotateSession(ClanWarBoardSession session) throws IOException
	{
		return parseSessionResponse(post("/api/plugin/session/rotate", "{}", authenticatedHeaders(session.getToken())));
	}

	String postAvailability(ClanWarBoardSession session, String json) throws IOException
	{
		return post("/api/plugin/availability", json, authenticatedHeaders(session.getToken()));
	}

	String postChallenge(ClanWarBoardSession session, String json) throws IOException
	{
		return post("/api/plugin/challenges", json, authenticatedHeaders(session.getToken()));
	}

	String fetchChallenges(ClanWarBoardSession session) throws IOException
	{
		return get("/api/plugin/challenges", authenticatedHeaders(session.getToken()));
	}

	String postChallengeAction(ClanWarBoardSession session, String challengeId, String json) throws IOException
	{
		return post("/api/plugin/challenges/" + safeId(challengeId) + "/actions", json, authenticatedHeaders(session.getToken()));
	}

	static String challengeActionJson(String action, String reason)
	{
		String normalized = action == null ? "" : action.trim().toLowerCase();
		if ("dispute".equals(normalized))
		{
			return "{\"action\":\"dispute\",\"reasonCode\":\"other\",\"statement\":\"" + jsonEscape(reason) + "\"}";
		}
		return "{\"action\":\"" + jsonEscape(normalized) + "\"}";
	}

	String fetchModerationAudit(ClanWarBoardSession session, String challengeId) throws IOException
	{
		return get("/api/plugin/challenges/" + safeId(challengeId) + "/moderation", authenticatedHeaders(session.getToken()));
	}

	private static String safeId(String value)
	{
		String id = value == null ? "" : value.trim();
		if (!id.matches("[A-Za-z0-9-]{1,128}"))
		{
			throw new IllegalArgumentException("Invalid challenge id");
		}
		return id;
	}



	static String availabilityJson(String startsAt, String duration, String combatMin, String combatMax, String notes)
	{
		return availabilityJson(FightMode.CWA, startsAt, duration, combatMin, combatMax, notes);
	}

	static String availabilityJson(FightMode mode, String startsAt, String duration, String combatMin, String combatMax, String notes)
	{
		return "{\"startsAt\":\"" + jsonEscape(startsAt) + "\",\"durationMinutes\":" + number(duration, "30") +
			",\"combatMin\":" + number(combatMin, "70") + ",\"combatMax\":" + number(combatMax, "126") +
			",\"mode\":\"" + mode.apiValue() + "\",\"notes\":\"" + jsonEscape(notes) + "\"}";
	}

	static String challengeJson(String opponent, String startsAt, String duration, String combatMin, String combatMax, String world, String location, String rules)
	{
		return challengeJson(FightMode.CWA, opponent, startsAt, duration, combatMin, combatMax, world, location, rules);
	}

	static String challengeJson(FightMode mode, String opponent, String startsAt, String duration, String combatMin, String combatMax, String world, String location, String rules)
	{
		return "{\"opponentClanId\":\"" + jsonEscape(opponent) + "\",\"terms\":{" +
			"\"location\":\"" + jsonEscape(location) + "\",\"world\":" + number(world, "0") +
			",\"startsAt\":\"" + jsonEscape(startsAt) + "\",\"combatMin\":" + number(combatMin, "70") +
			",\"combatMax\":" + number(combatMax, "126") + ",\"durationMinutes\":" + number(duration, "30") +
			",\"mode\":\"" + mode.apiValue() + "\",\"returnsAllowed\":" + mode.isReturnsAllowed() +
			",\"rules\":\"" + jsonEscape(rules) + "\"}}";
	}

	private List<WarBoardFight> parseFights(String json, String collection)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
		if (root == null || !root.has(collection) || !root.get(collection).isJsonArray())
		{
			throw new IllegalArgumentException("Fight response is missing " + collection);
		}
		JsonArray rows = root.getAsJsonArray(collection);
		List<WarBoardFight> fights = new ArrayList<>();
		for (JsonElement element : rows)
		{
			JsonObject row = element.getAsJsonObject();
			fights.add(new WarBoardFight(string(row, "id"), string(row, "creatorClanId"), string(row, "opponentClanId"),
				string(row, "startsAt"), integer(row, "durationMinutes"), integer(row, "combatMin"), integer(row, "combatMax"),
				string(row, "notes"), string(row, "status"), "wildy".equalsIgnoreCase(string(row, "mode")) ? FightMode.WILDY : FightMode.CWA));
		}
		return fights;
	}

	private static String string(JsonObject object, String name)
	{
		return object != null && object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
	}

	private static int integer(JsonObject object, String name)
	{
		try
		{
			return object != null && object.has(name) ? object.get(name).getAsInt() : 0;
		}
		catch (RuntimeException ignored)
		{
			return 0;
		}
	}

	private static String normalizeClanId(String value)
	{
		return value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
	}

	private static String number(String value, String fallback)
	{
		try
		{
			return Integer.toString(Integer.parseInt(value == null ? "" : value.trim()));
		}
		catch (NumberFormatException ignored)
		{
			return fallback;
		}
	}

	static String registrationJson(String installId, ClanAccess access, String pluginVersion)
	{
		return "{\"installId\":\"" + jsonEscape(installId) +
			"\",\"playerName\":\"" + jsonEscape(access.getPlayerName()) +
			"\",\"clanName\":\"" + jsonEscape(access.getClanName()) +
			"\",\"clanRank\":" + access.getRankValue() +
			",\"pluginVersion\":\"" + jsonEscape(pluginVersion) +
			"\"}";
	}

	static Map<String, String> authenticatedHeaders(String token)
	{
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Authorization", "Bearer " + token);
		headers.put("X-CWB-Timestamp", Long.toString(Instant.now().getEpochSecond()));
		headers.put("X-CWB-Nonce", UUID.randomUUID().toString());
		return headers;
	}

	ClanWarBoardSession parseSession(String json)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
		if (root == null || !root.has("sessionToken") || !root.has("expiresAt")
			|| !root.has("capabilities") || !root.get("capabilities").isJsonArray())
		{
			throw new IllegalArgumentException("Session response is missing required fields");
		}
		String token = root.get("sessionToken").getAsString();
		String expiresAt = root.get("expiresAt").getAsString();
		if (token.trim().isEmpty() || expiresAt.trim().isEmpty())
		{
			throw new IllegalArgumentException("Session token and expiry are required");
		}
		Set<String> capabilities = new java.util.HashSet<>();
		for (JsonElement capability : root.getAsJsonArray("capabilities"))
		{
			String value = capability.getAsString().trim();
			if (!value.isEmpty())
			{
				capabilities.add(value);
			}
		}
		return new ClanWarBoardSession(token, OffsetDateTime.parse(expiresAt).toInstant(), capabilities);
	}

	private ClanWarBoardSession parseSessionResponse(String json) throws IOException
	{
		try
		{
			return parseSession(json);
		}
		catch (RuntimeException ex)
		{
			throw new IOException("Clan War Board returned a malformed session", ex);
		}
	}

	private String get(String path) throws IOException
	{
		return get(path, Collections.emptyMap());
	}

	private String get(String path, Map<String, String> headers) throws IOException
	{
		Request.Builder builder = new Request.Builder()
			.url(serviceUrl + path)
			.header("Accept", "application/json")
			.header("User-Agent", USER_AGENT)
			.header("X-Clan-War-Board-Client", "runelite");
		headers.forEach(builder::header);
		return execute(builder.build());
	}

	private String post(String path, String json, Map<String, String> headers) throws IOException
	{
		Request.Builder builder = new Request.Builder()
			.url(serviceUrl + path)
			.header("Accept", "application/json")
			.header("User-Agent", USER_AGENT)
			.header("X-Clan-War-Board-Client", "runelite")
			.post(RequestBody.create(JSON, json));
		headers.forEach(builder::header);
		return execute(builder.build());
	}

	private String execute(Request request) throws IOException
	{
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException("Clan War Board API returned HTTP " + response.code());
			}
			return response.body() == null ? "" : response.body().string();
		}
	}


	private static String jsonEscape(String value)
	{
		if (value == null)
		{
			return "";
		}
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	static int countOccurrences(String text, String token)
	{
		if (text == null || text.isEmpty() || token == null || token.isEmpty())
		{
			return 0;
		}
		int count = 0;
		int offset = 0;
		while ((offset = text.indexOf(token, offset)) >= 0)
		{
			count++;
			offset += token.length();
		}
		return count;
	}
}
