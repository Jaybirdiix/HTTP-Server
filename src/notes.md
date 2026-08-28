
this satisfies
- config basics (parts a and c)
- basic request parsing
- basic static file serving
- http response / request has most things but NOT last-modified

does not handle
- error handling
- request header support reqs
  - accept
  - user-agent content selection rule
  - if-modified since conditional transfer + http date parsing
  - connection - close / keep-alive
    - we always close and don't parse it
  - authorization: basic ...
  - post + content-length + content-type for CGI only
- URL integrity
  - proper url decoding and normalization checks
  - canonical path check (resolved file must stay under docroot)
- index / moblie selection logic
  - if url ends with / return index.html if exists else 404
    - we do this
  - if request is for docroot (empty or /) and UA indicates iphone, try index_m.html first then index.html
- content negotiation with accept
  - if resource type not in accept header --> correct error response (typically 406)
- authorization via .htaccess
  - if .htaccess exists in directory of mapped resource, require correct Basic auth matching the base64 user/pass listed there
  - bonus: send WWW-Authenticate when missing
- CGI execution + chunked encoding
  - if mapped file is executable, run it as CGI, set minimal RFC}875 env vars, stream output back
  - for CGI allow chunked transfer instead of content-length
- Timout management + load
  - part b requires
  - 3s timeout for incomplete requests
  - management thread 'shutdown' draining existing requests
  - /load virtual URL returns 200 or 503 depending on accepting new connections
- select-loop parses a request based on \r\n\r\n and then assumes it's compelete and sends a response
  - missing handling pratial reads robustly
  - handling header size limits
  - supporting multiple requests on keep-alive connections

```java
public class SelectorLoop implements Runnable {
    // implements Runnable means this class promises it has a run() method with that signature
    // lets me run it on a thread like this
    SelectorLoop loop = new SelectorLoop(...);
    Thread t = new Thread(loop);
    t.start(); // calls loop.run() on a new 
    
    

}
```

QUESTIONS
- should ask about GeT case...
- ask about accept test cases
  - The server should understand the Accept header. It is OK if your server can handle only a list of concrete mime types, without wildcard or q values.
  - can we have a list of mime types that are acceptable?
- should we keep-alive by default ? (1.1)
- need to try multiple connections at once
- add auth to cgi
- how much should we chunk by ?
- can i always chunk for cgi 
- should test that requests complete after we request a shutdown

- overload number
- if keep alive, should close on server error?
- should the connection close on 'con' after three seconds?



What's left in part a
- parts b and c
  - adding threadpool ???
- writing comparisons part

```java
// not sure if this will mess things up
if (!control.accepting.get()) {
    client.close();
    break;
}
```

giving an undecodable string for auth causes 500