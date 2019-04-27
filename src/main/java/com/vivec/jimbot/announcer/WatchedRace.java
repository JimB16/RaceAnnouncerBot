package com.vivec.jimbot.announcer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.vivec.jimbot.irc.srl.RaceSplitTimeListener;
import com.vivec.jimbot.irc.srl.SpeedrunsliveIRCConnectionManager;
import com.vivec.jimbot.irc.twitch.TwitchIRCConnectionManager;
import com.vivec.jimbot.irc.twitch.TwitchJim;
import com.vivec.jimbot.srl.SpeedrunsliveAPI;
import com.vivec.jimbot.srl.api.Entrant;
import com.vivec.jimbot.srl.api.Game;
import com.vivec.jimbot.srl.api.PlayerState;
import com.vivec.jimbot.srl.api.Race;
import com.vivec.jimbot.srl.api.RaceState;
import org.json.JSONException;
import org.kitteh.irc.client.library.Client;

import com.cavariux.twitchirc.Chat.Channel;

import com.vivec.jimbot.announcer.gamesplits.PKMNREDBLUE;

public class WatchedRace {
	
	private List<Entrant> runnersConnectedThroughLiveSplit = new ArrayList<Entrant>();
	private List<String> announcedSplits = new ArrayList<String>();
	private Game game;
	private List<RaceSplit> splits;
	private String raceId;
	private Client srlClient;
	private TwitchJim twitchClient;
	private SpeedrunsliveAPI api;
	private String srlLiveSplitChannelName;
	private org.kitteh.irc.client.library.element.Channel srlLiveSplitChannel;
	
	private ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1); 
	
	public WatchedRace(Race race) throws InterruptedException {
		this.splits = new ArrayList<>();
		this.raceId = race.getId();
		this.game = race.getGame();
		this.api = new SpeedrunsliveAPI();
		this.srlClient = SpeedrunsliveIRCConnectionManager.getInstance().getIRCClient();
		this.srlLiveSplitChannelName = "#srl-" + this.raceId + "-livesplit";
		srlClient.addChannel(this.srlLiveSplitChannelName);
		do {
			TimeUnit.SECONDS.sleep(5);
		} while(srlClient.getChannel(this.srlLiveSplitChannelName).isPresent());

		srlLiveSplitChannel = srlClient.getChannel(srlLiveSplitChannelName)
				.orElseThrow(() -> new IllegalArgumentException("LiveSplit channel could not be found"));

		this.twitchClient = TwitchIRCConnectionManager.getInstance().getIRCClient();
		exec.scheduleAtFixedRate(new RaceStateChecker(this), 0, 1, TimeUnit.MINUTES);
	}
	
	private void initialize() {
		Race race = this.api.getSingleRace(this.raceId);
		this.srlClient.getEventManager().registerEventListener(new RaceSplitTimeListener());
		List<String> usersInLiveSplitChannel = this.srlLiveSplitChannel.getNicknames();
		for(Entrant e : race.getEntrants()) {
			if(usersInLiveSplitChannel.contains(e.getUserName())) {
				this.runnersConnectedThroughLiveSplit.add(e);
				this.twitchClient.joinChannel(e.getTwitch().toLowerCase());
				this.twitchClient.sendMessage(CommonMessages.CHANNEL_ANNOUNCEMENT_JOIN, Channel.getChannel(e.getTwitch().toLowerCase(), this.twitchClient));
			}
		}
	}
	
	public void recordSplitTime(String splitName, String user, String time) {
		Entrant e = findEntrantByUsername(user);
		if(e == null) {
			e = getRunnerInRaceFromAPI(user);
			this.addRunner(e);
		}
		if(e != null) {
			if(this.getGame().getId() == 6) {
				String standardSplitName = PKMNREDBLUE.getSplitNameByNameOrAlias(splitName);
				if(standardSplitName != null) {
					System.out.println("Found standardized name for " + splitName + ", using " + standardSplitName);
					splitName = standardSplitName;
				}
			}
			RaceSplit rs = findRaceSplitByName(splitName);
			if(rs == null) {
				rs = new RaceSplit(splitName);
				this.splits.add(rs);
			}
			rs.addTime(e, time);

			// check all splits in case someone dropped as last runner
			for(RaceSplit rs2 : this.splits) {
				this.announceSplitIfComplete(rs2);
			}
		}
	}
	
	private Entrant getRunnerInRaceFromAPI(String user) {
		Race race = api.getSingleRace(this.raceId);
		for(Entrant e : race.getEntrants()) {
			if(e.getUserName().equalsIgnoreCase(user)) {
				return e;
			}
		}
		return null;
	}
	
	private void announceSplitIfComplete(RaceSplit rs) {
		if(this.announcedSplits.contains(rs.getSplitName())) {
			System.out.println("Split " + rs.getSplitName() + " has already been announced, not announcing again.");
			return;
		}
		if(this.getGame().getId() == 6) {
			PKMNREDBLUE split = PKMNREDBLUE.getSplitDataByNameOrAlias(rs.getSplitName());
			if(split != null && !split.isAnnounce()) {
				System.out.println("Split " + split.getName() + " is configured to not be announced.");
				return;
			}
		}
		Race race = this.api.getSingleRace(this.raceId);
		for(Entrant e : this.runnersConnectedThroughLiveSplit) {
            Optional.ofNullable(findEntrantByUsername(e.getUserName(), race.getEntrants()))
                    .ifPresent(e::updateData);
			if(e.getState() == PlayerState.FORFEIT) {
				continue;
			}
			if(rs.findTimeForRunner(e) == null) {
				System.out.println(e.getDisplayName() + " is not finished with the split " + rs.getSplitName() + " yet, not announcning.");
				return;
			}
		}
		
		System.out.println("Announcing split " + rs.getSplitName());
		
		String message = "Split " + rs.getSplitName() + " completed. Times: ";
		int position = 0;
		for(RaceSplit.SplitTime st : rs.getSplitTimes()) {
			message += ++position + ". " + st.getEntrantName() + " : " + st.getDisplayTime() + " | ";
		}
		
		for(Entrant e : this.getRunnersConnectedThroughLiveSplit()) {
			if(e.getState() != PlayerState.FORFEIT) {
				System.out.println("Sending times to " + e.getTwitch());
				this.twitchClient.sendMessage(message, Channel.getChannel(e.getTwitch().toLowerCase(), this.twitchClient));
			}
		}
		this.announcedSplits.add(rs.getSplitName());
	}
	
	private Entrant findEntrantByUsername(String user) {
		for(Entrant e : this.runnersConnectedThroughLiveSplit) {
			if(e.getUserName().equalsIgnoreCase(user)) {
				return e;
			}
		}
		return null;
	}
	
	private Entrant findEntrantByUsername(String user, List<Entrant> entrants) {
		for(Entrant e : entrants) {
			if(e.getUserName().equalsIgnoreCase(user)) {
				return e;
			}
		}
		return null;
	}
	
	private RaceSplit findRaceSplitByName(String splitName) {
		for(RaceSplit rs : this.splits) {
			if(rs.getSplitName().equalsIgnoreCase(splitName)) {
				return rs;
			}
		}
		return null;
	}
	
	public void finishRace() {
		System.out.println("Finishing up race, parting all channels associated with this race.");
		for(Entrant e : this.getRunnersConnectedThroughLiveSplit()) {
			this.twitchClient.partChannel(e.getTwitch());
			System.out.println("Left channel " + e.getTwitch());
		}
		this.srlClient.removeChannel(srlLiveSplitChannelName);
	}
	
	public List<Entrant> getRunnersConnectedThroughLiveSplit() {
		return runnersConnectedThroughLiveSplit;
	}
	
	public Game getGame() {
		return game;
	}

	public List<RaceSplit> getSplits() {
		return splits;
	}

	public String getRaceId() {
		return raceId;
	}

	public SpeedrunsliveAPI getApi() {
		return api;
	}

	public ScheduledThreadPoolExecutor getExec() {
		return exec;
	}
	
	public void addRunner(Entrant e) {
		this.getRunnersConnectedThroughLiveSplit().add(e);
		this.twitchClient.joinChannel(e.getTwitch().toLowerCase());
	}
	
	private class RaceStateChecker implements Runnable{

		private WatchedRace wr;
		public RaceStateChecker(WatchedRace wr) {
			this.wr = wr;
		}
		
		@Override
		public void run() {
			Race race = wr.api.getSingleRace(wr.raceId);
			if(race.getState() == RaceState.IN_PROGRESS) {
				System.out.println("Initializing the race");
				wr.initialize();
				exec.shutdown();
			} else {
				System.out.println("Race " + wr.raceId + " has not started yet, waiting a minute. " + LocalDateTime.now().toString());
			}
		}
		
	}
	
}
