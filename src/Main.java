import java.io.*;
import java.nio.*; // hm
import java.net.*;
import java.util.*;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;



public class Main {
    public static void main(String[] args) throws Exception {
        // this is the default, will be overwritten if a file is provided
        // String configPath = "test-http.conf";
        // default if none specified
        String configPath = "test-http-threads.conf";
        // for each argument
        for (int i=0; i < args.length - 1; i++) {
            // if the argument is -config, then the path is the one coming after it
            if (args[i].equals("-config")) {
                configPath = args[i+1];
            }
        }

        // this gets the configuration from the default file or whatever the user lists
        System.out.println("Using config file: " + configPath);

        HttpConfig config = ConfigParser.parse(configPath);

        // System.out.println("CONFIG: nSelectLoops=" + config.nSelectLoops() + " nThreads=" + config.nThreads());

        // opens a server-side socket endpoint that can accept incoming TCP connections
        ServerSocketChannel server = ServerSocketChannel.open();
        // binds the server socket to the local port sepcified in the config
        // this is what makes the program start listening for incoming connections on that port
        server.bind(new InetSocketAddress(config.listenPort()));
        // blocking mode
        server.configureBlocking(true);

        // whether or not we're accepting new connections + number of active connections
        ServerState serverState = new ServerState();

        Thread managementThread = new Thread(new ManagementConsole(serverState, server));
        managementThread.setDaemon(true);
        managementThread.start();

        if (config.nSelectLoops() > 0) {
            // select loops
            List<SelectorLoop> loops = SelectorLoop.startLoops(config.nSelectLoops(), config, serverState);
            Acceptor acceptor = new Acceptor(server, loops, serverState);
            acceptor.run();
        } else {
            System.out.println("Using threads");
            // threadpool
            ExecutorService pool = Executors.newFixedThreadPool(config.nThreads());

            Acceptor acceptor = new Acceptor(server, pool, config, serverState);

            acceptor.run();

            // If acceptor exits (shutdown), stop pool
            pool.shutdown();
        }

    }


}

