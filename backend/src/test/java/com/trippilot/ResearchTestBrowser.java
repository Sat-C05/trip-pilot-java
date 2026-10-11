package com.trippilot;

import static org.junit.jupiter.api.Assertions.*;
import com.jayway.jsonpath.JsonPath;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

class ResearchTestBrowser implements AutoCloseable {
    final int port;
    final HttpClient http;
    final String email="phase2-"+UUID.randomUUID()+"@example.invalid";
    String owner;
    ResearchTestBrowser(int port) {
        this.port=port;
        http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
    }
    HttpResponse<String> call(String method,String path,String body,boolean csrf,String type,String lastId) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(35));
        if(csrf) {
            var token=get("/api/identity/csrf");
            request.header(JsonPath.read(token.body(),"$.headerName"),JsonPath.read(token.body(),"$.token"));
        }
        if(type!=null) request.header("Content-Type",type);
        if(lastId!=null) request.header("Last-Event-ID",lastId);
        request.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String path) throws Exception { return call("GET",path,null,false,null,null); }
    HttpResponse<String> post(String path,String body) throws Exception { return call("POST",path,body,true,"application/json",null); }
    void signIn() throws Exception {
        var registered=post("/api/identity/register","{\"email\":\""+email+"\",\"password\":\"synthetic-phase2-password-42\"}");
        assertEquals(201,registered.statusCode());owner=JsonPath.read(registered.body(),"$.id");
        assertEquals(204,call("POST","/api/identity/login","username="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password=synthetic-phase2-password-42",true,"application/x-www-form-urlencoded",null).statusCode());
    }
    UUID session() throws Exception {
        var response=post("/api/planning/sessions","{\"title\":\"Synthetic phase two research\"}");
        assertEquals(201,response.statusCode());return UUID.fromString(JsonPath.read(response.body(),"$.id"));
    }
    UUID revision(UUID session) throws Exception {
        var response=post("/api/planning/sessions/"+session+"/revisions","{\"departure\":\"Delhi\",\"destinationIntent\":\"Jaipur\",\"country\":\"IN\",\"preferences\":[]}");
        assertEquals(201,response.statusCode(),response.body());return UUID.fromString(JsonPath.read(response.body(),"$.id"));
    }
    UUID job(UUID session,UUID revision,String key) throws Exception {
        var response=post("/api/planning/sessions/"+session+"/jobs","{\"revisionId\":\""+revision+"\",\"requestKey\":\""+key+"\"}");
        assertEquals(202,response.statusCode(),response.body());return UUID.fromString(JsonPath.read(response.body(),"$.id"));
    }
    @Override public void close() { http.shutdownNow(); }
}
