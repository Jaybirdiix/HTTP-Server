
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.channels.ClosedChannelException;

import java.util.concurrent.ExecutorService;



public class Acceptor {
    private final ServerSocketChannel server;
    private final List<SelectorLoop> loops;
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    ServerState serverState;

    // thread mode
    private final ExecutorService threadPool;
    private final HttpConfig config;

    // important settings
    int maxConnections = 5;
    int allowConnectionsAgain = 2;


    // loops
    public Acceptor (ServerSocketChannel server, List<SelectorLoop> loops, ServerState serverState) {
        this.server = server;
        this.loops = loops;
        this.serverState = serverState;
        this.threadPool = null;
        this.config = null;
    }

    // threads
    public Acceptor(ServerSocketChannel server, ExecutorService threadPool, HttpConfig config, ServerState serverState) {

        this.server = server;
        this.loops = null;
        this.serverState = serverState;
        this.threadPool = threadPool;
        this.config = config;
    }

    
    public void run() throws Exception {
        System.out.println("Listening on " + server.getLocalAddress());

        while (true) {

            // If overloaded, pause accepting until we drop low enough
            while (!serverState.accepting.get()) {
                if (serverState.activeConnections.get() <= allowConnectionsAgain) {
                    serverState.accepting.set(true);
                    System.out.println("Server is accepting connections again.");
                    break;
                }
                Thread.sleep(50);
            }

            // Before accepting the next connection, enforce the limit
            if (serverState.activeConnections.get() >= maxConnections) {
                serverState.accepting.set(false);
                System.out.println("Server has stopped accepting new connections.");
                continue;
            }

            SocketChannel client = null;
            try {
                client = server.accept(); // blocking

                // re-check after accept in case state flipped
                if (!serverState.accepting.get()) {
                    client.close();
                    continue;
                }

                // add connection
                serverState.activeConnections.incrementAndGet();

                if (threadPool != null) {
                    // THREAD
                    // System.out.println("Using threads");
                    client.configureBlocking(true);
                    threadPool.submit(new ThreadWorker(client, config, serverState));
                } else {
                    // SELECT
                    // System.out.println("Using selector");
                    client.configureBlocking(false);
                    int index = Math.floorMod(roundRobin.getAndIncrement(), loops.size());
                    loops.get(index).register(client);
                }


            } catch (ClosedChannelException e) {
                return; // shutdown
            } catch (Exception e) {
                if (client != null) {
                    try { client.close(); } catch (Exception ignored) {}
                    serverState.activeConnections.decrementAndGet();
                }
            }

        }
    }


}