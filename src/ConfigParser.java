

// server -config <file>

// how you tell your server how to behave before any client connects
// server side settings the program reads at startup
// when the server starts, what port should it listen on and what hostnames should it serve? where are the files for each hostname?

// Listen 6789
// nSelectLoops 2

// <VirtualHost *:6789>
//   DocumentRoot ./host1-root
//   ServerName host1.cs.yale.edu
// </VirtualHost>  

// <VirtualHost *:6789>
//   DocumentRoot ./host2-root
//   ServerName host2.cs.yale.edu
// </VirtualHost> 

// this is saying the server should bind to port 6789 and listen there. nSelectLoops says the server should create 2 select/epoll event loops
// - select instead of threadpool mode

// then we define a virtual host (a "site") 
// the ServerName host1.cs.yale.edu is selected when the request has Host: host1.cs.yale.edu
// the DocumentRoot ./host1-root means when that host is selected, map URLs to files inside that directory

// request looks something like:
// GET /index.html HTTP/1.1
// Host: host1.cs.yale.edu

// The server:
// picks which virtualhost to use
// looks at the Host: header
// if Host: host1.cs.yale.edu, choose the virtual host whose ServerName matches
// if there's no Host Header or it doesn't match any ServerName, choose the first Virtual Host in the config (the default)
// Maps URL to the filesystem path
// DocumentRoot is ./host1-root
// URL is /index.html
// Mapped path becomes ./host1-root/index.html
// Serve that file (or an error)
// If it exists and is readable, respond with 200 OK with headers and content
// otherwise error code
//

import java.io.*;
import java.util.*;

// record defines simple structs
record VirtualHost(String serverName, String documentRoot) {}

// define config structure
record HttpConfig(int listenPort, int nSelectLoops, int nThreads, List<VirtualHost> virtualHosts) {}

// // <VirtualHost *:6789>
// //   DocumentRoot ./host1-root
// //   ServerName host1.cs.yale.edu
// // </VirtualHost>  

class ConfigParser {
  public static HttpConfig parse(String path) throws IOException {
    // backup defaults
    int listenPort = 6789;
    int nSelectLoops = 0;
    int nThreads = 0;

    List<VirtualHost> vhosts = new ArrayList<>();

    try (BufferedReader br = new BufferedReader(new FileReader(path))) {
      String line;

      boolean inVirtualHost = false;
      String documentRoot = null;
      String serverName = null;

      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) continue;

        String[] parts = line.split("\\s+");

        if (!inVirtualHost && line.startsWith("Listen")) {
          listenPort = Integer.parseInt(parts[1]);

        } else if (!inVirtualHost && line.startsWith("nSelectLoops")) {
          nSelectLoops = Integer.parseInt(parts[1]);

        } else if (!inVirtualHost && line.startsWith("nThreads")) {
            nThreads = Integer.parseInt(parts[1]);


        } else if (line.startsWith("<VirtualHost")) {
          inVirtualHost = true;
          documentRoot = null;
          serverName = null;

        } else if (line.startsWith("</VirtualHost")) {
          inVirtualHost = false;

          if (serverName == null || documentRoot == null) {
            throw new IllegalArgumentException("VirtualHost missing ServerName or DocumentRoot");
          }
          vhosts.add(new VirtualHost(serverName, documentRoot));

        } else if (inVirtualHost && line.startsWith("DocumentRoot")) {
          documentRoot = parts[1];

        } else if (inVirtualHost && line.startsWith("ServerName")) {
          serverName = parts[1];
        }
      }
    }

    if (vhosts.isEmpty()) {
      throw new IllegalArgumentException("No VirtualHost blocks found in config");
    }

    if (nSelectLoops > 0 && nThreads > 0) {
        throw new IllegalArgumentException("Config error: choose either nSelectLoops or nThreads, but not both");
    }
    if (nSelectLoops <= 0 && nThreads <= 0) {
        throw new IllegalArgumentException("Config error: you have to specify nSelectLoops is greater than 0 or nThreads > 0");
    }


    return new HttpConfig(listenPort, nSelectLoops, nThreads, vhosts);

  }
}

// compile
// javac -d out src/*.java

// run
// java -cp out Main -config test-http.conf

