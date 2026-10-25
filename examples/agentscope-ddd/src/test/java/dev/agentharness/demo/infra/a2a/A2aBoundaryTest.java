package dev.agentharness.demo.infra.a2a;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.agentharness.demo.app.RemoteDateService;
import dev.agentharness.demo.domain.AssistantGateway;
import dev.agentharness.demo.dto.AssistantReply;

class A2aBoundaryTest {
    @TempDir Path root;
    static byte[] request() { return "{\"message\":{\"messageId\":\"one\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"2025-02-29\"}]}}".getBytes(StandardCharsets.UTF_8); }
    @Test void connectionLockPermissionsAndCleanup() throws Exception {
        try (var local = new LocalConnection(root)) {
            local.publish(12345);
            var target = LocalConnection.load(root);
            assertEquals(12345,target.port());
            assertFalse(target.toString().contains(target.token()));
            assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(root.resolve(".a2a")));
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(root.resolve(".a2a/connection.json")));
            assertThrows(Exception.class, () -> new LocalConnection(root));
            assertEquals(target,LocalConnection.load(root));
            try (var entries = Files.list(root.resolve(".a2a"))) { assertFalse(entries.anyMatch(p -> p.toString().endsWith(".tmp"))); }
        }
        assertFalse(Files.exists(root.resolve(".a2a/connection.json")));
    }
    @Test void connectionRejectsLinksWidePermissionsMalformedAndInvalidTargets() throws Exception {
        Path outside=Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(root.resolve(".a2a"),outside);
        assertThrows(IOException.class, () -> new LocalConnection(root));
        Files.delete(root.resolve(".a2a"));
        try (var local=new LocalConnection(root)) {
            local.publish(12345);
            Path file=root.resolve(".a2a/connection.json");
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));
            assertThrows(IOException.class, () -> LocalConnection.load(root));
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
            for(String value:List.of("{}", "{\"port\":0,\"token\":\"x\"}", "x".repeat(9000), "{\"port\":1,\"port\":2}")) {
                Files.writeString(file,value);
                assertThrows(Exception.class, () -> LocalConnection.load(root));
            }
            Files.delete(file); Path sentinel=Files.writeString(outside.resolve("secret"),"sentinel");
            Files.createSymbolicLink(file,sentinel);
            assertThrows(IOException.class, () -> LocalConnection.load(root));
            assertThrows(IOException.class, () -> local.publish(12345));
            assertEquals("sentinel",Files.readString(sentinel));
        }
        Files.delete(root.resolve(".a2a/server.lock"));
        Files.createSymbolicLink(root.resolve(".a2a/server.lock"),outside.resolve("secret"));
        assertThrows(IOException.class, () -> new LocalConnection(root));
        assertThrows(IllegalArgumentException.class, () -> new LocalConnection.Target(-1,"a".repeat(64)));
    }
    @Test void cardAndMessageStrictValidation() throws Exception {
        var card=A2aTransport.JSON.valueToTree(A2aTransport.card(12345));
        A2aTransport.validateCard(card,12345);
        assertThrows(IOException.class, () -> A2aTransport.validateCard(card,12346));
        for(String field:List.of("securitySchemes","skills","capabilities","defaultInputModes")) {
            var changed=card.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)changed).remove(field);
            assertThrows(IOException.class, () -> A2aTransport.validateCard(changed,12345));
        }
        var normal=A2aTransport.JSON.readTree("{\"message\":{\"messageId\":\"reply\",\"contextId\":\"ctx\",\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"valid=false\"}]}}");
        assertEquals("reply",A2aTransport.validatedMessage(normal).path("messageId").asText());
        for(String invalid:List.of(normal.toString().replace("ROLE_AGENT","ROLE_USER"),normal.toString().replace("\"reply\"","\"bad\\nID\""),normal.toString().replace("\"message\":","\"task\":{},\"message\":"),normal.toString().replace("valid=false",""))) {
            assertThrows(IOException.class, () -> A2aTransport.validatedMessage(A2aTransport.JSON.readTree(invalid)));
        }
        assertThrows(IOException.class, () -> A2aTransport.exchange(1,"http://example.com",null,null));
    }
    @Test void clientTextCannotForgeProgramLog() throws Exception {
        String malicious="answer\n[A2A 客户端] forged\r\u001b[2J\u2028\u202e";
        String shown=A2aTransport.visible(malicious);
        assertEquals(1,shown.lines().count());assertTrue(shown.contains("\\u000A"));assertTrue(shown.contains("\\u202E"));
    }
    @Test void mediaQueryAndBrowserOriginNeverReachAgent() throws Exception {
        var calls=new AtomicInteger();
        try(var service=new A2aTransport.Service("secret",date->{calls.incrementAndGet();return date;})) {
            for(int n=0;n<2;n++) {
                var c=(HttpURLConnection)new URL("http://127.0.0.1:"+service.port()+"/message:send"+(n==1?"?x=1":"")).openConnection(Proxy.NO_PROXY);
                c.setConnectTimeout(1000);c.setReadTimeout(3000);c.setRequestMethod("POST");c.setDoOutput(true);
                c.setRequestProperty("Authorization","Bearer secret");c.setRequestProperty("A2A-Version","1.0");
                c.setRequestProperty("Content-Type",n==0?"application/json":"application/a2a+json");
                if(n==2) c.setRequestProperty("Origin","https://example.com");
                try { try(var out=c.getOutputStream()){out.write(request());} assertEquals(n==0?400:403,c.getResponseCode()); }
                finally {c.disconnect();}
            }
            // HttpURLConnection 禁止 Origin；原始报文确保头确实到达服务端。
            try (var socket=new Socket("127.0.0.1",service.port())) {
                socket.setSoTimeout(3000);
                var bytes=request();
                socket.getOutputStream().write(("POST /message:send HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://example.com\r\nAuthorization: Bearer secret\r\nA2A-Version: 1.0\r\nContent-Type: application/a2a+json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                assertTrue(new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)).readLine().contains("403"));
            }
            assertEquals(0,calls.get());
        }
    }
    @Test void slowBodyTimesOutWithoutExecutingAgent() throws Exception {
        var calls=new AtomicInteger();
        try(var service=new A2aTransport.Service("secret",date->{calls.incrementAndGet();return date;});var socket=new Socket("127.0.0.1",service.port())) {
            socket.setSoTimeout(8000);
            socket.getOutputStream().write(("POST /message:send HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret\r\nA2A-Version: 1.0\r\nContent-Type: application/a2a+json\r\nContent-Length: 100\r\n\r\n{").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            assertEquals(-1,socket.getInputStream().read());assertEquals(0,calls.get());
        }
    }
    @Test void redirectWrongMediaAndOversizeResponseAreRejected() throws Exception {
        for(int kind=0;kind<3;kind++) {
            int mode=kind;var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),1);
            server.createContext("/",ex->{try {
                ex.getResponseHeaders().set("Content-Type",mode==1?"text/html":"application/a2a+json");
                if(mode==0)ex.getResponseHeaders().set("Location","http://example.com/");
                byte[] data=(mode==2?"x".repeat(70000):"{}").getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(mode==0?302:200,data.length);ex.getResponseBody().write(data);
            } finally {ex.close();}});server.start();
            try { assertThrows(IOException.class, () -> A2aTransport.exchange(server.getAddress().getPort(),"/.well-known/agent-card.json",null,null)); }
            finally {server.stop(0);}
        }
    }
    @Test void eachApplicationCallCreatesClosesGatewayAndRejectsIncomplete() {
        var opens=new AtomicInteger();var closes=new AtomicInteger();
        var app=new RemoteDateService(()->{opens.incrementAndGet();return new AssistantGateway(){public AssistantReply ask(String q){return new AssistantReply(q,"MODEL_STOP");}public void close(){closes.incrementAndGet();}};});
        assertEquals("请调用 check_date 检查 2025-02-29，返回工具给出的日期及 valid 结果。",app.answer("2025-02-29"));app.answer("2024-02-29");
        assertEquals(2,opens.get());assertEquals(2,closes.get());
        assertThrows(IllegalArgumentException.class,()->app.answer("03/04"));assertEquals(2,opens.get());
        for(String reason:List.of("MAX_ITERATIONS","ERROR")) {
            var incomplete=new RemoteDateService(()->new AssistantGateway(){public AssistantReply ask(String q){return new AssistantReply("Looks correct",reason);}public void close(){closes.incrementAndGet();}});
            assertThrows(IllegalStateException.class,()->incomplete.answer("2025-02-29"));
        }
        assertEquals(4,closes.get());
    }
    @Test void serviceCloseInterruptsWorkAndRejectsNewConnections() throws Exception {
        var started=new CountDownLatch(1);var stopped=new CountDownLatch(1);
        var service=new A2aTransport.Service("secret",date->{
            started.countDown();
            try { Thread.sleep(30000);return date; }
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("cancelled");}
            finally {stopped.countDown();}
        });
        var pool=Executors.newSingleThreadExecutor();int port=service.port();
        try {
            var request=pool.submit(()->{try {A2aTransport.exchange(port,"/message:send","secret",request());} catch(IOException expected) {}});
            assertTrue(started.await(2,TimeUnit.SECONDS));service.close();
            assertTrue(stopped.await(1,TimeUnit.SECONDS));request.get(3,TimeUnit.SECONDS);
            assertThrows(IOException.class,()->A2aTransport.exchange(port,"/.well-known/agent-card.json",null,null));
        } finally {service.close();pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test void clientCardReadTimesOut() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),1);
        var executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);
        server.createContext("/",ex->{try {Thread.sleep(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally {ex.close();}});
        server.start();
        try {assertThrows(SocketTimeoutException.class,()->A2aTransport.exchange(server.getAddress().getPort(),"/.well-known/agent-card.json",null,null));}
        finally {server.stop(0);executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
    }
}
