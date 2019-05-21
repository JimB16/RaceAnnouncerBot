package com.vivec.jimbot.announcer;

import com.cavariux.twitchirc.Chat.Channel;
import com.vivec.jimbot.JimBot162v2;
import com.vivec.jimbot.announcer.gamesplits.PKMNREDBLUE;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kitteh.irc.client.library.Client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.vivec.jimbot.announcer.RaceSplit.SplitTime;

import static com.vivec.jimbot.announcer.gamesplits.PKMNREDBLUE.getSplitDataByNameOrAlias;
import static com.vivec.jimbot.announcer.gamesplits.PKMNREDBLUE.getSplitNameByNameOrAlias;

public class WatchedRace {

    private static final Logger LOG = LogManager.getLogger(WatchedRace.class);
    private final List<Entrant> runnersConnectedThroughLiveSplit = new ArrayList<>();
    private final List<String> spectators = new ArrayList<>();
    private final List<String> deltaPlus = new ArrayList<>();
    private final List<String> announcedSplits = new ArrayList<>();
    private final Game game;
    private final List<RaceSplit> splits = new ArrayList<>();
    private final String raceId;
    private final Client srlClient = SpeedrunsliveIRCConnectionManager.getInstance().getIRCClient();
    private final TwitchJim twitchClient = TwitchIRCConnectionManager.getInstance().getIRCClient();
    private final SpeedrunsliveAPI api = new SpeedrunsliveAPI();
    private final String srlLiveSplitChannelName;
    private String standings;
    List<SplitTime> allSplitTimes = new ArrayList<SplitTime>();
    private org.kitteh.irc.client.library.element.Channel srlLiveSplitChannel;

    private ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1);

    public WatchedRace(Race race) {
        raceId = race.getId();
        game = race.getGame();
        srlLiveSplitChannelName = "#srl-" + raceId + "-livesplit";
        srlClient.addChannel(srlLiveSplitChannelName);
        exec.scheduleAtFixedRate(new RaceStateChecker(this), 0, 1, TimeUnit.MINUTES);
        setStandings("No standings available yet.");
    }

    public void recordSplitTime(String splitName, String user, String time) {
        PKMNREDBLUE standardSplitData = PKMNREDBLUE.getSplitDataByNameOrAlias(splitName);
        Entrant e = Optional.ofNullable(findEntrantByUsername(user))
                .orElse(getRunnerInRaceFromAPI(user));
        addEntrantToLiveSplitCollection(e);
        if (e != null) {
            if (getGame().getId() == 6) {
                String standardSplitName = getSplitNameByNameOrAlias(splitName);
                if (standardSplitName != null) {
                    LOG.info("Found standardized name for {}, using {}", splitName, standardSplitName);
                    splitName = standardSplitName;
                }
            }
            if(standardSplitData != null) {
                RaceSplit raceSplit = Optional.ofNullable(findRaceSplitByName(splitName))
                        .orElse(new RaceSplit(splitName, standardSplitData.getOrderNr()));
                raceSplit.addTime(e, time);
                getSplits().add(raceSplit);


                Boolean deltaPlus = this.deltaPlus.contains(e.getTwitch());
                if(deltaPlus) {
                    announceSplitDelta(raceSplit, e);
                }
            }
        }

        // check all splits in case someone dropped as last runner
        getSplits().forEach(this::announceSplitIfComplete);

        getAllRaceSplits();
    }

    private void addEntrantToLiveSplitCollection(Entrant e) {
        if (runnersConnectedThroughLiveSplit.stream()
                .noneMatch(ent -> ent.getUserName().equalsIgnoreCase(e.getUserName()))) {
            joinTwitchChannelAndSendWelcome(e);
        }
    }

    private void announceSplitDelta(RaceSplit raceSplit, Entrant e) {
        String deltaMsg = "Empty DeltaString";
        SplitTime st_pre = null;
        SplitTime st_now = null;
        int position = 1;
        for(SplitTime st : raceSplit.getSplitTimes()) {
            position++;
            st_pre = st_now;
            st_now = st;
            if(st.getEntrantName().equalsIgnoreCase(e.getDisplayName())) {
                if(st_pre != null) { // someone is in front of the runner
                    deltaMsg = st_pre.getSplitName() + ": " + (position-1) + ". " + st_pre.getEntrantName() + " " + st_pre.getDisplayTime() + " | ";
                    long delta = st_now.getTime() - st_pre.getTime();
                    deltaMsg += (position+0) + ". " +  st.getEntrantName() + " ";
                    if(delta > 0) deltaMsg += "+";
                    deltaMsg += getDisplayTime(delta);
                } else { // no one is in front of the runner
                    deltaMsg = st.getSplitName() + ": " + position + ". " + st.getEntrantName() + " " + st.getDisplayTime() + " | ";
                }
            }
        }
        LOG.info("Sending delta time to {}", e.getTwitch());
        twitchClient.sendMessage(deltaMsg, Channel.getChannel(e.getTwitch().toLowerCase(), twitchClient));
    }

    public void getAllRaceSplits() {
        this.allSplitTimes.clear();

        // collect all Split Times that were posted by the runners
        for(RaceSplit rs : this.splits) {
            this.allSplitTimes.addAll(rs.getSplitTimes());
        }

        Collections.sort(this.allSplitTimes, new RaceSplit.SplitTimeComparator()); // sort all SplitTimes

        List<String> runnerNames = new ArrayList<>();

        Iterator<SplitTime> it = this.allSplitTimes.iterator();
        // iterate through all SplitTimes
        while (it.hasNext()) {
            SplitTime st = it.next();
            if(!runnerNames.contains(st.getEntrantName())) { // found first (most progressed after sorting) SplitTime of the runner
                runnerNames.add(st.getEntrantName());
            } else { // else found a slower SplitTime that is removed
                it.remove();
            }
        }

        // Output the remaining SplitTimes (sorted and only one per Runner)
        LOG.info("Announcing Standings");

        String message = "Standings: ";
        int position = 0;
        for(SplitTime st : this.allSplitTimes) {
            message += ++position + ". " + st.getEntrantName() + " : " + st.getSplitName() + " (";
            if(findEntrantByUsername(st.getEntrantName()).getState() ==  PlayerState.FORFEIT) {
                message += "forfeited";
            } else {
                message += st.getDisplayTime();
            }
            message += ") ";
            message += " | ";
        }
        LOG.info("{}", message);

        this.setStandings(message);
    }

    public String getDeltaMsg(String srlUserName) {
        String deltaMsg = "Empty String";
        deltaMsg = srlUserName;
        for(SplitTime st : this.allSplitTimes) {
            if(st.getEntrantName().equalsIgnoreCase(srlUserName)) {
                String splitName = st.getSplitName();
                deltaMsg = st.getEntrantName() + " " + st.getSplitName() + " " + st.getDisplayTime();
                for(RaceSplit rs : this.splits) {
                    if(rs.getSplitName().equalsIgnoreCase(splitName)) {
                        SplitTime st_pre = null;
                        SplitTime st_now = null;
                        SplitTime st_next = null;
                        for(SplitTime st2 : rs.getSplitTimes()) {
                            st_pre = st_now;
                            st_now = st2;
                            if(st_pre != null) { // someone is in front of the runner
                                deltaMsg = st_pre.getSplitName() + ": " + st_pre.getEntrantName() + " " + st_pre.getDisplayTime() + " | ";
                                if (st2.getEntrantName().equalsIgnoreCase(srlUserName)) {
                                    long delta = st_now.getTime() - st_pre.getTime();
                                    deltaMsg += st2.getEntrantName() + " ";
                                    if(delta > 0) deltaMsg += "+";
                                    deltaMsg += getDisplayTime(delta);
                                }
                            } else { // no one is in front of the runner
                                deltaMsg = st2.getSplitName() + " " + st2.getEntrantName() + " " + st2.getDisplayTime() + " | ";
                            }
                        }
                    }
                }
            }
        }
        return deltaMsg;
    }

    private String getDisplayTime(long time) {
        long second = (time / 1000) % 60;
        long minute = (time / (1000 * 60)) % 60;
        long hour = (time / (1000 * 60 * 60));
        long millis = time % 1000;
        if(hour > 0) {
            return String.format("%d:%02d:%02d.%d", hour, minute, second, millis);
        } else {
            return String.format("%02d:%02d.%d", minute, second, millis);
        }
    }

    private Entrant getRunnerInRaceFromAPI(String user) {
        Race race = api.getSingleRace(raceId);
        return race.getEntrants()
                .stream()
                .filter(e -> e.getUserName().equalsIgnoreCase(user))
                .findFirst()
                .orElse(null);
    }

    private void announceSplitIfComplete(RaceSplit rs) {
        if (announcedSplits.contains(rs.getSplitName())) {
            LOG.warn("Split {} has already been announced, not announcing again.", rs.getSplitName());
            return;
        }
        if (getGame().getId() == 6) {
            PKMNREDBLUE split = getSplitDataByNameOrAlias(rs.getSplitName());
            if (split != null && !split.isAnnounce()) {
                LOG.debug("Split {} is configured to not be announced.", split.getName());
                return;
            }
        }
        Race race = api.getSingleRace(raceId);
        final String message = buildSegmentAnnouncement(rs);
        List<Entrant> activeRunners = getRunnersThatHaveNotForfeit(race);
        boolean allActiveRunnersCompleted = activeRunners.stream().allMatch(e -> rs.findTimeForRunner(e) != null);

        if (allActiveRunnersCompleted) {
            LOG.info("Announcing split {}", rs.getSplitName());
            if(!JimBot162v2.DEBUG) {
                activeRunners.forEach(e -> sendAnnouncementToEntrant(message, e));
            } else {
                LOG.info("{}", message);
            }
            announcedSplits.add(rs.getSplitName());
        }
    }

    private List<Entrant> getRunnersThatHaveNotForfeit(Race race) {
        return getRunnersConnectedThroughLiveSplit()
                .stream()
                .map(e -> updateEntrantData(race, e))
                .filter(e -> PlayerState.FORFEIT != e.getState())
                .collect(Collectors.toList());
    }

    private void sendAnnouncementToEntrant(String message, Entrant e) {
        LOG.info("Sending times to {}", e.getTwitch());
        twitchClient.sendMessage(message, Channel.getChannel(e.getTwitch().toLowerCase(), twitchClient));
    }

    private String buildSegmentAnnouncement(RaceSplit rs) {
        StringBuilder message = new StringBuilder("Segment " + rs.getSplitName() + " completed. Times: ");
        int position = 0;
        for (RaceSplit.SplitTime st : rs.getSplitTimes()) {
            message.append(++position)
                    .append(". ")
                    .append(st.getEntrantName())
                    .append(" : ")
                    .append(st.getDisplayTime())
                    .append(" | ");
        }
        return message.toString();
    }

    private Entrant updateEntrantData(Race race, Entrant e) {
        Optional.ofNullable(findEntrantByUsername(e.getUserName(), race.getEntrants()))
                .ifPresent(e::updateData);
        return e;
    }

    private Entrant findEntrantByUsername(String user) {
        return findEntrantByUsername(user, runnersConnectedThroughLiveSplit);
    }

    private Entrant findEntrantByUsername(String user, List<Entrant> entrants) {
        return entrants.stream()
                .filter(e -> e.getUserName().equalsIgnoreCase(user))
                .findFirst()
                .orElse(null);
    }

    public Entrant findEntrantByTwitchName(String user) {
        return findEntrantByTwitchName(user, runnersConnectedThroughLiveSplit);
    }

    private Entrant findEntrantByTwitchName(String user, List<Entrant> entrants) {
        return entrants.stream()
                .filter(e -> e.getTwitch().equalsIgnoreCase(user))
                .findFirst()
                .orElse(null);
    }

    private RaceSplit findRaceSplitByName(String splitName) {
        return getSplits().stream()
                .filter(rs -> rs.getSplitName().equalsIgnoreCase(splitName))
                .findFirst()
                .orElse(null);
    }

    void finishRace() {
        LOG.info("Finishing up race, parting all channels associated with this race.");
        
        getRunnersConnectedThroughLiveSplit()
                .forEach(e -> {
                    twitchClient.partChannel(e.getTwitch());
                    LOG.debug("Left channel {}", e.getTwitch());
                });
        srlClient.removeChannel(srlLiveSplitChannelName);

        spectators.forEach(s ->{
            twitchClient.partChannel(s);
            LOG.debug("Left channel {}", s);
        });
        spectators.clear();
    }

    List<Entrant> getRunnersConnectedThroughLiveSplit() {
        return runnersConnectedThroughLiveSplit;
    }

    private Game getGame() {
        return game;
    }

    private List<RaceSplit> getSplits() {
        return splits;
    }

    String getRaceId() {
        return raceId;
    }

    void setStandings(String standings) {
        this.standings = standings;
    }

    public String getStandings() {
        return standings;
    }

    public SpeedrunsliveAPI getApi() {
        return api;
    }

    public List<String> getSpectators() {
        return spectators;
    }

    public int addSpectator(String spectatorname) {
        if(!spectators.contains(spectatorname)) {
            spectators.add(spectatorname);
            twitchClient.joinChannel(spectatorname);
            return 1;
        }
        return 0;
    }

    public void remSpectator(String spectatorname) {
        spectators.remove(spectatorname);
        twitchClient.partChannel(spectatorname);
    }

    public List<String> getDeltaPlus() {
        return deltaPlus;
    }

    public int addDeltaPlus(String name) {
        if(!deltaPlus.contains(name)) {
            deltaPlus.add(name);
            //twitchClient.joinChannel(name);
            return 1;
        }
        return 0;
    }

    private void joinTwitchChannelAndSendWelcome(Entrant e) {
        runnersConnectedThroughLiveSplit.add(e);
        twitchClient.joinChannel(e.getTwitch().toLowerCase());
        if(!JimBot162v2.DEBUG) {
            twitchClient.sendMessage(CommonMessages.CHANNEL_ANNOUNCEMENT_JOIN, Channel.getChannel(e.getTwitch().toLowerCase(), twitchClient));
        }
    }

    private class RaceStateChecker implements Runnable {

        private WatchedRace wr;

        RaceStateChecker(WatchedRace wr) {
            this.wr = wr;
        }

        @Override
        public void run() {
            Race race = wr.api.getSingleRace(wr.raceId);
            if (race.getState() == RaceState.IN_PROGRESS) {
                LOG.info("Initializing the race");
                initialize();
                exec.shutdown();
            } else {
                LOG.info("Race {} has not started yet, waiting a minute.", wr.raceId);
            }
        }

        private void initialize() {
            Race race = api.getSingleRace(raceId);
            srlClient.getEventManager().registerEventListener(new RaceSplitTimeListener());
            initLiveSplitChannel();
            List<String> usersInLiveSplitChannel = srlLiveSplitChannel.getNicknames();
            race.getEntrants()
                    .stream()
                    .filter(e -> usersInLiveSplitChannel.contains(e.getUserName().toLowerCase()))
                    .forEach(WatchedRace.this::joinTwitchChannelAndSendWelcome);
        }

        private void initLiveSplitChannel() {
            Optional<org.kitteh.irc.client.library.element.Channel> livesplitSRL;
            do {
                livesplitSRL = srlClient.getChannel(srlLiveSplitChannelName);
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } while (!livesplitSRL.isPresent());
            srlLiveSplitChannel = livesplitSRL.orElseThrow(() -> new IllegalArgumentException("LiveSplit channel could not be found"));
        }
    }

}
