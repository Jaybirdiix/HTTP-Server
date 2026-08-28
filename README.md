
### Compile & Run

#### SelectorLoop
```bash
javac -d out src/*.java && java -cp out Main -config test-http.conf
```

#### Threads
```bash
javac -d out src/*.java && java -cp out Main -config test-http-threads.conf
```

#### Shutdown
```bash
Listening on /[0:0:0:0:0:0:0:0]:6789
shutdown
```

#### Testing

I have a whole tests folder, though I realized many of them are overkill. I think one invalid http request test fails at the moment, but the rest should pass. The filename indicates what the request is testing.

In a new terminal:
```bash
./test.sh ./tests/*
# or
./test.sh ./tests/specificTest.http
```

Note that the testing scripts (./test.sh, ./concurrent.sh) are AI generated ! I figured that was okay as it's not formally part of the assignment. I also generated a script to test the class test cases. It required some modifications but it's a great check to ensure everything is working as it should.

Class test cases:

```bash
./run_all_tests.sh
```

### Code

There are three bash scripts in http-meeting. `concurrent.sh` is used to test whether or not the server returns 503 after opening a bunch of connections. RIGHT NOW THE AMOUNT OF ACTIVE CONNECTIONS IS LIMITED TO FIVE. To begin accepting new connections after hitting this limit, the amount of active connections must reach TWO.

`Acceptor.java`
```java
int maxConnections = 5;
int allowConnectionsAgain = 2;
```

This is an arbitrary limit; I was just testing the /load functionality and wanted to set a cap somewhere. These caps can very easily be increased.

`test.sh` runs tests on the server from a new terminal.

`run.sh` is my simple little 'compile and run with a particular config' script.

`./tests` holds a bunch of .http files to test the server with.

`./src` holds almost all of my code.

#### `./src` (tried to put them in order!)
- `Main.java`
  - Entry point of the program. This loads the config file with a default if none is specified. It also creates the ServerSocketChannel, starts the ManagementConsole and runs either in selector or threadpool mode (calling Acceptor).
- `ConfigParser.java`
  - This reads the provided (or default) config file and returns an HttpConfig object with all the relevant information.
- `Acceptor.java`
  - This blocks on server.accept() and sends each connection either to a ThreadWorker or registers it with a SelectorLoop. It also sets a limit on the maximum number of connections.
- `ServerState.java`
  - I couldn't figure out how to place this in another file in a way that made sense so this is the entire file:
```java
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ServerState {
    public final AtomicBoolean accepting = new AtomicBoolean(true);
    public final AtomicInteger activeConnections = new AtomicInteger(0);
}
```
  - The variables must be atomic to ensure that there aren't any errors with so many threads / selectorLoops writing to the same variables.
  - This tracks whether the server will take new connections and how many active connections it currently has.
- `ManagementConsole.java`
  - This is a background thread that reads stdin and responds to 'shutdown'. When it reads the shutdown command, it tells the server to stop taking new connections and waits for activeConnections to reach zero before exiting.
- `SelectorLoop.java`
  - Each SelectorLoop has a non-blocking event loop that handles all the SocketChannels it has been assigned with a Selector. It buffers incoming bytes and waits until it has a complete request (with body if POST) before calling RouterAndStatic to create the response. When it has a response, the SelectorLoop will write it out.
- `ThreadWorker.java`
  - This reads one request at a time and calls RouterAndStatic to create the response. From there, it writes it and loops if keep-alive is true. The connection is blocking and each ThreadWorker has its own.
- `HttpRequest.java`
  - This parses the incoming request and returns an HttpRequest object with all relevant information.
- `RouterAndStatic.java`
  - This is the main router. It deals with requests, determines how to respond, and crafts a response. It chooses the virtual host based on the `Host` header, serves static files, and handles If-Modified-Since, Accept, Auth, etc. It handles the `/load` endpoint and forwards .cgi requests to CgiHandler.java.
- `CgiHandler.java`
  - This handles .cgi, including POST. It sets the environment variables, runs the script, reads output and converts it into an HTTP response. These responses are chunked.
- `AuthConfigParser.java`
  - This handles .htaccess settings. It looks at the authentication type and returns an AuthConfig object that RouterAndStatic uses to validate that the user is authorized to access the file.


The two .md files were just my notes and can be ignored.

### Other

These are just some diagrams I made (somewhat incomplete) during the early stages while I was still working everything out. It's missing a lot, but it has most of the basic structure so I thought I'd include it!

![Image](./mainMap.png)

And this was just to help me understand the ins and outs:

![Image](./serverInOut.png)


    EIGHT FAILS