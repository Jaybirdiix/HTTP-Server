








telnet 127.0.0.1 6789
GET / HTTP/1.1
Host: host1.cs.yale.edu

telnet 127.0.0.1 6789
GET / HTTP/1.1
Host: host1.cs.yale.edu
Accept: text/html


// iphone

GET / HTTP/1.1
Host: host2.cs.yale.edu
Accept: text/html
User-Agent: iphone

GET / HTTP/1.1
If-Modified-Since: Sat, 01 Jan 2100 00:00:00 GMT


GET / HTTP/1.1
If-Modified-Since: Sat, 01 Jan 2000 00:00:00 GMT



GET / HTTP/1.1
Host: host1.cs.yale.edu
If-Modified-Since: Sat, 01 Jan 2000 00:00:00 GMT

GET / HTTP/1.1
Host: host1.cs.yale.edu
If-Modified-Since: Sat, 01 Jan 2000 00:00:00 GMT


GET /protect/ HTTP/1.1
Host: host1.cs.yale.edu
Authorization: Basic YWxpY2U6cGFzc3cwcmQ=


Authorization: Basic Y3M0MzQ6cGFzc3cwcmQu












COMPILE
cd http-meeting/
javac -d out src/*.java

RUN 
java -cp out Main -config test-http.conf



