

// server -config <file>

// when the server starts, what port should it listen on and what hostnames should it serve? where are the files for each hostname?

import java.io.*;
import java.util.*;

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

