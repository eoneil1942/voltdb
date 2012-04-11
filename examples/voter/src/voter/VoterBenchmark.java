/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
/*
 * This samples uses the native asynchronous request processing protocol
 * to post requests to the VoltDB server, thus leveraging to the maximum
 * VoltDB's ability to run requests in parallel on multiple database
 * partitions, and multiple servers.
 *
 * While asynchronous processing is (marginally) more convoluted to work
 * with and not adapted to all workloads, it is the preferred interaction
 * model to VoltDB as it guarantees blazing performance.
 *
 * Because there is a risk of 'firehosing' a database cluster (if the
 * cluster is too slow (slow or too few CPUs), this sample performs
 * auto-tuning to target a specific latency (5ms by default).
 * This tuning process, as demonstrated here, is important and should be
 * part of your pre-launch evaluation so you can adequately provision your
 * VoltDB cluster with the number of servers required for your needs.
 */

package voter;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.voltdb.VoltDB;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ClientStats;
import org.voltdb.client.ClientStatusListenerExt;
import org.voltdb.client.ProcedureCallback;

import voter.procedures.Vote;

public class VoterBenchmark {

    static class VoterConfig extends Configuration {
        @Option(opt = "display-interval",
                desc = "Interval for performance feedback, in seconds.")
        long displayInterval = 10;

        @Option(desc = "Benchmark duration, in seconds.")
        int duration = 120;

        @Option(desc = "List of VoltDB servers to connect to.")
        int port = 21212;

        @Option(opt = "contestants",
                desc = "Number of contestants in the voting contest (from 1 to 10).")
        int contestantCount = 6;

        @Option(opt = "max-votes",
                desc = "Interval for performance feedback, in seconds.")
        int maxVoteCount = 10;

        @Option(opt = "rate-limit",
                desc = "Interval for performance feedback, in seconds.")
        int rateLimit = 100000;

        @Option(opt = "auto-tune",
                desc = "Interval for performance feedback, in seconds.")
        boolean autoTune = true;

        @Option(opt = "latency-target",
                desc = "Interval for performance feedback, in seconds.")
        int latencyTarget = 5;

        @Override
        public void validate() {
            if (duration <= 0) exitWithMessageAndUsage("duration must be > 0");
            if (displayInterval <= 0) exitWithMessageAndUsage("display-interval must be > 0");
            if (contestantCount <= 0) exitWithMessageAndUsage("contestants must be > 0");
            if (maxVoteCount <= 0) exitWithMessageAndUsage("max-votes must be > 0");
            if (rateLimit <= 0) exitWithMessageAndUsage("rate-limit must be > 0");
            if (latencyTarget <= 0) exitWithMessageAndUsage("latency-target must be > 0");
        }
    }

    class StatusListener extends ClientStatusListenerExt {
        @Override
        public void connectionLost(String hostname, int port, int connectionsLeft, DisconnectCause cause) {
            // TODO Auto-generated method stub
            super.connectionLost(hostname, port, connectionsLeft, cause);
        }
    }

    class VoterCallback implements ProcedureCallback {
        @Override
        public void clientCallback(ClientResponse response) throws Exception {
            if (response.getStatus() == ClientResponse.SUCCESS) {
                long resultCode = response.getResults()[0].asScalarLong();
                if (resultCode == Vote.ERR_INVALID_CONTESTANT) {
                    badContestantVotes.incrementAndGet();
                }
                else if (resultCode == Vote.ERR_VOTER_OVER_VOTE_LIMIT) {
                    badVoteCountVotes.incrementAndGet();
                }
                else {
                    assert(resultCode == Vote.VOTE_SUCCESSFUL);
                    acceptedVotes.incrementAndGet();
                }
            }
            else {
                failedVotes.incrementAndGet();
            }
        }
    }

    // Initialize some common constants and variables
    static final String CONTESTANT_NAMES_CSV =
            "Edwina Burnam,Tabatha Gehling,Kelly Clauss,Jessie Alloway," +
            "Alana Bregman,Jessie Eichman,Allie Rogalski,Nita Coster," +
            "Kurt Walser,Ericka Dieter,Loraine NygrenTania Mattioli";

    static final String HORIZONTAL_RULE =
            "-------------------------------------------------------------------------------------\n";

    // Reference to the database connection we will use
    final VoterConfig config;
    final Client client;
    PhoneCallGenerator switchboard;
    VoterCallback callback = new VoterCallback();

    // voter benchmark state
    AtomicLong acceptedVotes = new AtomicLong(0);
    AtomicLong badContestantVotes = new AtomicLong(0);
    AtomicLong badVoteCountVotes = new AtomicLong(0);
    AtomicLong failedVotes = new AtomicLong(0);

    public VoterBenchmark(VoterConfig config) {
        this.config = config;

        ClientConfig clientConfig = new ClientConfig("", "", new StatusListener());
        if (config.autoTune) {
            clientConfig.enableAutoTune();
            clientConfig.setAutoTuneTargetInternalLatency(config.latencyTarget);
        }
        else {
            clientConfig.setMaxTransactionsPerSecond(config.rateLimit);
        }
        client = ClientFactory.createClient(clientConfig);
        switchboard = new PhoneCallGenerator(config.contestantCount);
    }

    void connect(String server, int port) {
        int sleep = 1000;
        while (true) {
            try {
                client.createConnection(server, port);
                break;
            }
            catch (Exception e) {
                System.err.printf("Connection failed - retrying in %d second(s).\n", sleep / 1000);
                try { Thread.sleep(sleep); } catch (Exception interruted) {}
                if (sleep < 8000) sleep += sleep;
            }
        }
        System.out.printf("Connected to %s on port %d.\n", server, port);
    }

    public void runBenchmark() throws Exception {
        // connect
        connect("localhost", VoltDB.DEFAULT_PORT);


        // initialize using synchronous call
        ClientResponse response = client.callProcedure("Initialize",
                                                       config.contestantCount,
                                                       CONTESTANT_NAMES_CSV);


        // Create a Timer task to display performance data on the Vote procedure
        // It calls printStatistics() every displayInterval seconds
        Timer timer = new Timer();
        TimerTask statsPrinting = new TimerTask() {
            @Override
            public void run() { printStatistics(); }
        };
        timer.scheduleAtFixedRate(statsPrinting,
                                  config.displayInterval * 1000,
                                  config.displayInterval * 1000);


        // Run the benchmark loop for the requested duration
        final long endTime = System.currentTimeMillis() + (1000l * config.duration);
        while (endTime > System.currentTimeMillis()) {
            // Get the next phone call
            PhoneCallGenerator.PhoneCall call = switchboard.receive();

            client.callProcedure(callback,
                                 "Vote",
                                 call.phoneNumber,
                                 call.contestantNumber,
                                 config.maxVoteCount);
        }

        timer.cancel(); // cancel periodic stats printing

        // print the summary results
        printResults();

        // close down the client connections
        client.close();
    }

    public synchronized void printStatistics() {
        ClientStats stats = client.getStats(true, true, true)[0];
        System.out.println(stats.benchmarkUpdate(System.currentTimeMillis()));
    }

    public synchronized void printResults() {
        ClientStats stats = client.getStats(false, true, true)[0];

        // 1. Voting Board statistics, Voting results and performance statistics
        String display = "\n" +
                         HORIZONTAL_RULE +
                         " Voting Results\n" +
                         HORIZONTAL_RULE +
                         "A total of %d votes were received...\n" +
                         " - %,9d Accepted\n" +
                         " - %,9d Rejected (Invalid Contestant)\n" +
                         " - %,9d Rejected (Maximum Vote Count Reached)\n" +
                         " - %,9d Failed (Transaction Error)\n\n";

        System.out.printf(display, stats.invocationsCompleted,
                acceptedVotes.get(), badContestantVotes.get(),
                badVoteCountVotes.get(), failedVotes.get());

        long now = System.currentTimeMillis();
        System.out.printf("For %.1f seconds, an average throughput of %d txns/sec was sustained.\n",
                (now - stats.since) / 1000.0, stats.throughput(now));
        System.out.printf("Average latency was %d ms per procedure.\n", stats.averageLatency());
        System.out.printf("Average internal latency, as reported by the server was %d ms.\n", stats.averageInternalLatency());
        System.out.printf("Measured 95th and 99th percentile latencies were %d and %d ms respectively\n",
                stats.kPercentileLatency(.95), stats.kPercentileLatency(.99));
    }

    static void printUsage(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("VoterBenchmark", options);
    }

    public static void main(String[] args) throws Exception {
        VoterConfig config = new VoterConfig();
        config.parse(args);
        config.validate();

        VoterBenchmark benchmark = new VoterBenchmark(config);
        benchmark.runBenchmark();
    }
}
