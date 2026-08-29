
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;



// this represents a single worker thread loop 
public class SelectorLoop implements Runnable {
    // so this is the selector
    private final Selector selector;
    // queue of sockets waiting to be officially registered
    private final ConcurrentLinkedQueue<SocketChannel> pendingRegistrations = new ConcurrentLinkedQueue<>();
    private final ServerState serverState;


    private final HttpConfig config;

    private SelectorLoop(HttpConfig config, ServerState serverState) throws IOException {
        this.config = config;
        // creates a new Selector instance
        this.selector = Selector.open();
        this.serverState = serverState;
    }

    // takes in a number (of threads) and the config
    // returns a list of selector loop objects
    // it also starts RUNNING all of these threads and that behavour is defined in run()
    public static List<SelectorLoop> startLoops(int n, HttpConfig config, ServerState serverState) throws IOException {

        List<SelectorLoop> loops = new ArrayList<>();
        // define an array of selector loops to return
        // iterate through n

        for (int i = 0; i < n; i++) {
            SelectorLoop loop = new SelectorLoop(config, serverState);
            Thread t = new Thread(loop, "selector-" + i);
            t.start();
            loops.add(loop);
        }
        return loops;
    }

    public void register(SocketChannel channel) {
        // add the channel to the concurrentlinked queue
        pendingRegistrations.add(channel);
        selector.wakeup();
    }

    @Override
    public void run() {
        try {
            while (true) {
                
                // register newly accepted sockets
                SocketChannel new_connection; // this is where we store a new connection
                
                // for each new connection waiting in pending registrations
                while ((new_connection = pendingRegistrations.poll()) != null) {
                    // socket's memory
                    ConnectionState state = new ConnectionState();
                    // register this socket with this Selector
                    // tells the selector:
                    // please watch this channel
                    // wake me up when it's ready to READ
                    // and when you tell me it's ready please return this state attatchment
                    try {
                        long currentTimeNS = System.nanoTime();
                        state.acceptedAtNS = currentTimeNS;
                        state.requestDeadlineNS = currentTimeNS + 3_000_000_000L; // three seconds
                        state.requestComplete = false;
                        new_connection.register(selector, SelectionKey.OP_READ, state);
                    } catch (Exception e) {
                        try { new_connection.close(); } catch (Exception ignored) {}
                        serverState.activeConnections.decrementAndGet();
                    }
                }

                selector.select(50); // blocks until at least one registered channel is ready for an operation that I asked for (read)

                // when selector.select() RETURNS it stores the registered channels that are ready for something into SelectionKey objects into the selector's selected-key set.

                // selectionkey holds ---------
                // a socket
                // a selector (the one it's registered with)
                // interestOps (read / write)
                // readyOps (what is ready right now, set by .select())
                // Object attachment (ConnectionState)
                //boolean valid;

                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    
                    if (!key.isValid()) continue;

                    if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }

                }

                long now = System.nanoTime();
                for (SelectionKey key : selector.keys()) {
                    if (!key.isValid()) continue;
                    ConnectionState state = (ConnectionState) key.attachment();
                    if (state == null) continue;

                    if (!state.requestComplete && now >= state.requestDeadlineNS) {
                        SocketChannel ch = (SocketChannel) key.channel();
                        myCloseConnection(key, ch);
                    }
                }



                // when selector.wakeup() is called (like it is upon the registration of a new socket) this returns immediately if it's currently blocking
                // this allows the loop to notice the new socket and register it
            }

            

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel connection = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();

        // set connection state to an object
        int num_read = connection.read(state.readBuffer);
        // if error reading, close the channel
        if (num_read == -1) {
            myCloseConnection(key, connection);
            return;
        }

        // switch to read mode (read out bytes instead of write into this buffer)
        state.readBuffer.flip();
        // this is a StringBuilder object
        state.requestBytes.append(StandardCharsets.ISO_8859_1.decode(state.readBuffer));

        // set back to write mode
        state.readBuffer.clear();

        String buffered = state.requestBytes.toString();
        int endOfHeader = buffered.indexOf("\r\n\r\n");
        if (endOfHeader == -1) return; // haven't read headers yet

        // at this point we have at LEAST the headers, maybe more !!

        byte[] responseBytes;

        try {
            HttpRequest request = HttpRequestParser.parse(buffered);
            state.keepAlive = shouldKeepAlive(request);

            // preserve extra stuff (potentially from the next request)
            int contentLength = 0;
            if (request.method().equalsIgnoreCase("POST")) {
                contentLength = Integer.parseInt(request.headers().getOrDefault("content-length", "0").trim()); // if it's not there httprequest will send 411
            }
            int requestTotal = endOfHeader + 4 + contentLength;

            if (buffered.length() < requestTotal) {
                return; // need more body bytes
            }


            String nextRequest = "";

            if (buffered.length() > requestTotal) {
                nextRequest = buffered.substring(requestTotal);
            }

            responseBytes = RouterAndStatic.buildResponseBytes(request, config, serverState, state.keepAlive);
            
            state.requestBytes.setLength(0);
            state.requestComplete = true;
            state.requestBytes.append(nextRequest);

        } catch (HttpRequestParser.NeedMoreData e) {
            return; // wait for more bytes
        } catch (HttpRequestParser.HttpError e) {
            state.keepAlive = false;
            responseBytes = RouterAndStatic.simpleText(e.code, e.reason, e.body, "close");
        } catch (Exception e) {
            responseBytes = RouterAndStatic.simpleText(500, "Internal Server Error", "Server error\n", "close"); // NOT SURE IF SHOULD KEEP ALIVE HERE 
        }

        state.writeBuffer = ByteBuffer.wrap(responseBytes);
        key.interestOps(SelectionKey.OP_WRITE);

    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel connection = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();

        connection.write(state.writeBuffer);

        if (!state.writeBuffer.hasRemaining()) {

            state.writeBuffer = null;

            if (state.keepAlive) {
                // go back to read or close the connection
                state.requestComplete = false;
                state.requestDeadlineNS = System.nanoTime() + 3_000_000_000L;
                key.interestOps(SelectionKey.OP_READ);
            } else {
                myCloseConnection(key, connection);
            }

        }
    }

    private static class ConnectionState {
        ByteBuffer readBuffer = ByteBuffer.allocate(8192);
        StringBuilder requestBytes = new StringBuilder();
        ByteBuffer writeBuffer;
        boolean keepAlive = true;

        // timeout stuff
        // when it was accepted
        long acceptedAtNS;
        // when it should be completed by
        long requestDeadlineNS;
        // if it is complete
        boolean requestComplete;
    }

    public static boolean shouldKeepAlive(HttpRequest request) {
        String keepAlive = request.headers().getOrDefault("connection", "").trim();

        if (keepAlive.equalsIgnoreCase("close")) {
            return false;
        }
        return true;
    }

    private void myCloseConnection(SelectionKey key, SocketChannel connection) {
        try {
            key.cancel();
        } catch (Exception e) {}

        try {
            connection.close();
        } catch (IOException e) {
        } finally {
            // will happen even on an error in closing the connection
            this.serverState.activeConnections.decrementAndGet();
        }
    }


}