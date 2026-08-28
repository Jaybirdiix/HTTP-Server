import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.channels.ServerSocketChannel;

// runnable allows for thread.start()
public final class ManagementConsole implements Runnable {
    private final ServerState serverState;
    private final ServerSocketChannel server;

    public ManagementConsole(ServerState serverState, ServerSocketChannel server) {
        this.serverState = serverState;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            // these are the important try lines
            InputStreamReader inputReader = new InputStreamReader(System.in);
            BufferedReader reader = new BufferedReader(inputReader);

            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    return;
                }

                line = line.trim().toLowerCase();

                if (line.equals("shutdown")) {
                    System.out.println("Initiating shutdown.");
                    serverState.accepting.set(false);
                    try {
                        server.close();
                    } catch (Exception e) {}

                    // wait for connections to finish

                    while (serverState.activeConnections.get() > 0) {
                        Thread.sleep(50);
                    }
                    System.out.println("All requests completed, all connections closed. (exit)");
                    System.exit(0);

                } else if (!line.isEmpty()) {
                    System.out.println("Unknown command: " + line + ", use 'shutodwn' to shut down the server.");
                }

            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}