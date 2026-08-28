import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ServerState {
    public final AtomicBoolean accepting = new AtomicBoolean(true);
    public final AtomicInteger activeConnections = new AtomicInteger(0);
}
