package de.sebmey.jimbot;

import de.sebmey.jimbot.irc.CredentialManager;
import de.sebmey.jimbot.irc.twitch.TwitchIRCConnectionManager;
import de.sebmey.jimbot.irc.twitch.TwitchJim;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.json.JSONObject;

import de.sebmey.jimbot.irc.CredentialManager;
import de.sebmey.jimbot.irc.twitch.TwitchIRCConnectionManager;
import de.sebmey.jimbot.irc.twitch.TwitchJim;
import de.sebmey.jimbot.announcer.*;
import de.sebmey.jimbot.announcer.RaceSplit.*;
import de.sebmey.jimbot.srl.api.Entrant;
import de.sebmey.jimbot.srl.api.Race;

public class JimBot162v2 {

	/*public static void main(String[] args) {
		CredentialManager man = CredentialManager.getInstance();
		man.setBotUsername(args[0]);
		man.setBotTwitchOAuth(args[1]);
		man.setBotSRLPass(args[2]);
		TwitchJim jim = TwitchIRCConnectionManager.getInstance().getIRCClient();
		jim.start();
	}*/
	// https://jimb.de/TournamentStats/test2.php?q=abcde&t=srlraces
	public static void main(String[] args) {
		JSONObject entrantData = new JSONObject();
		entrantData.put("displayname", "testrunner");
		entrantData.put("place", 0);
		entrantData.put("time", 0);
		entrantData.put("message", "message");
		entrantData.put("statetext", "Ready");
		entrantData.put("twitch", "testrunner");
		entrantData.put("trueskill", 0);

		Entrant testrunner = new Entrant(entrantData, "TestRunner");
		// offline SplitTest
		RaceSplit rs = new RaceSplit("Lance");
		rs.addTime(testrunner,"1:20:20:00");
		rs.addTime(testrunner,"1:19:20:00");
		rs.addTime(testrunner,"1:44:20:00");
		rs.addTime(testrunner,"1:17:20:00");
		System.out.println("Announcing split " + rs.getSplitName());

		String message = "Split " + rs.getSplitName() + " completed. Times: ";
		int position = 0;
		for(SplitTime st : rs.getSplitTimes()) {
			message += ++position + ". " + " : " + st.getDisplayTime() + " | ";
		}
		System.out.println(message);


		HttpClient httpClient = HttpClientBuilder.create().build(); //Use this instead

		try {
			//String json = "{\"message\":\"This is a message\"}";
			String json = "{" +
					"  \"raceId\": \"abcde\"," +
					"  \"name\": {\"name\":\"abcde\"}," +
					"  \"splits\": { " +
					"  \"firstsplit\": [ " +
					"{ \"name\": \"runner1\", \"time\": \"1:20.03\"}," +
					"{ \"name\": \"runner2\", \"time\": \"1:33.03\"}" +
					"]," +
					" \"secondsplit\": [ " +
					"{ \"name\": \"runner2\", \"time\": \"1:40.03\"}," +
					"{ \"name\": \"runner1\", \"time\": \"1:53.03\"}" +
					"]" +
					"}" +
					"}";

			HttpPost request = new HttpPost("https://www.jimb.de/TournamentStats/test.php");
			//StringEntity params = new StringEntity("message={\"name\":\"jim\"} ");
			StringEntity params =new StringEntity("message=" + json);
			request.addHeader("content-type", "application/x-www-form-urlencoded");
			request.setEntity(params);
			HttpResponse response = httpClient.execute(request);

			//handle response here...

			System.out.println(org.apache.http.util.EntityUtils.toString(response.getEntity()));
			org.apache.http.util.EntityUtils.consume(response.getEntity());
		} catch (Exception ex) {
			//handle exception here
		}
	}
}
