package com.jarvys.factory.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.jarvys.factory.runtime.HttpJobEngine.*;

/** Actual NativeNetwork executes only against in-memory URL connections; no external request. */
public final class HttpJobNativeTransportTest {
    static final class Connection extends HttpURLConnection {
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();boolean disconnected;boolean fail;
        Connection(URL url){super(url);}
        public void disconnect(){disconnected=true;}
        public boolean usingProxy(){return false;}
        public void connect(){}
        public OutputStream getOutputStream(){return sent;}
        public int getResponseCode()throws IOException{if(fail)throw new IOException("ambiguous socket failure");return 200;}
        public Map<String,List<String>> getHeaderFields(){return Collections.emptyMap();}
        public String getContentType(){return "text/plain; charset=utf-8";}
        public InputStream getInputStream(){return new ByteArrayInputStream("native result".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        public long getContentLengthLong(){return 13;}
    }
    @Test public void reservationIsDurableBeforeActualConnectionAndResponseIsInert()throws Exception{
        HttpJobEngineTest.Fixture f=new HttpJobEngineTest.Fixture();f.enqueue();Connection[] opened={null};
        NativeNetwork network=new NativeNetwork(url->{assertEquals(State.RUNNING,f.memory.raw().state);Connection c=new Connection(url);opened[0]=c;return c;},host->false,System::nanoTime);
        f.transport=network::execute;RunResult result=f.fire();assertEquals(State.COMPLETED,result.row.state);assertEquals("one",opened[0].sent.toString("UTF-8"));assertTrue(opened[0].disconnected);
        assertEquals("native result",f.engine().readResult(HttpJobEngineTest.ID,false).getJSONObject("result").getString("body"));
    }
    @Test public void failedActualConnectionIsUnknownEvenWhenBodyMayHaveBeenSent()throws Exception{
        HttpJobEngineTest.Fixture f=new HttpJobEngineTest.Fixture();f.enqueue();Connection[] opened={null};
        NativeNetwork network=new NativeNetwork(url->{Connection c=new Connection(url);c.fail=true;opened[0]=c;return c;},host->false,System::nanoTime);
        f.transport=network::execute;RunResult result=f.fire();assertEquals(State.UNKNOWN,result.row.state);assertFalse(result.retryBeforeEffect);assertEquals("one",opened[0].sent.toString("UTF-8"));assertTrue(opened[0].disconnected);
    }
    @Test public void lostAuthorityBeforeOpenCannotReachHttpConnection()throws Exception{
        HttpJobEngineTest.Fixture f=new HttpJobEngineTest.Fixture();f.enqueue();int[] opens={0};
        NativeNetwork network=new NativeNetwork(url->{opens[0]++;return new Connection(url);},host->false,System::nanoTime);
        f.transport=(request,access,cancel)->{f.auth.alive=false;return network.execute(request,access,cancel);};
        assertEquals(State.UNKNOWN,f.fire().row.state);assertEquals(0,opens[0]);
    }
}
