package com.jarvys.factory.runtime;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.SpannableString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Staged for the final aggregate; not executed by the standalone synthetic test runner. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,28,32},manifest=Config.NONE)
public class IncomingShareCaptureTest {
    private static final IncomingSharePayload.Policy POLICY=new IncomingSharePayload.Policy(Collections.singleton("*/*"),true,IncomingSharePayload.Limits.defaults());
    private static final Uri URI=Uri.parse("content://fixture.provider/document/1");
    private interface Action {void run()throws Exception;}
    private static void invalid(Action action)throws Exception {try{action.run();fail();}catch(FactoryException expected){assertEquals("INVALID_SHARE",expected.code);}}
    private static Intent file(){return new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,URI).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);}
    @Test public void plainTextNeedsNoProviderOrGrant()throws Exception {
        AtomicInteger checks=new AtomicInteger();IncomingSharePayload value=IncomingShareCapture.capture(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"hello"),POLICY,uri->{checks.incrementAndGet();return false;});
        assertEquals("hello",value.text);assertEquals(0,checks.get());
    }
    @Test public void declaredReadFlagIsNotAndroidPermission()throws Exception {
        try{IncomingShareCapture.capture(file(),POLICY,uri->false);fail();}catch(FactoryException expected){assertEquals("PERMISSION_DENIED",expected.code);}
        invalid(()->IncomingShareCapture.capture(file().setFlags(0),POLICY,uri->true));
    }
    @Test public void acceptsExactRedundantClipAndStream()throws Exception {
        Intent value=file();value.setClipData(ClipData.newRawUri("Untrusted label",URI));
        assertEquals(Collections.singletonList(URI.toString()),IncomingShareCapture.capture(value,POLICY,uri->true).contentUris);
    }
    @Test public void acceptsClipOnlyContentWithoutLoadingIt()throws Exception {
        Intent value=new Intent(Intent.ACTION_SEND).setType("image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);value.setClipData(ClipData.newRawUri("label",URI));
        assertEquals(1,IncomingShareCapture.capture(value,POLICY,uri->true).contentUris.size());
    }
    @Test public void rejectsHiddenOrConflictingClipContent()throws Exception {
        Intent conflict=file();conflict.setClipData(ClipData.newRawUri("label",Uri.parse("content://fixture.provider/different")));
        invalid(()->IncomingShareCapture.capture(conflict,POLICY,uri->true));
        Intent nested=file();nested.setClipData(new ClipData("label",new String[]{"image/png"},new ClipData.Item(new Intent(Intent.ACTION_VIEW))));
        invalid(()->IncomingShareCapture.capture(nested,POLICY,uri->true));
        Intent html=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"text");html.setClipData(ClipData.newHtmlText("label","text","<b>text</b>"));
        invalid(()->IncomingShareCapture.capture(html,POLICY,uri->true));
    }
    @Test public void rejectsFilePathsArbitraryExtrasAndSpans()throws Exception {
        invalid(()->IncomingShareCapture.capture(file().putExtra(Intent.EXTRA_STREAM,Uri.parse("file:///sdcard/private")),POLICY,uri->true));
        invalid(()->IncomingShareCapture.capture(file().putExtra("arbitrary","secret"),POLICY,uri->true));
        invalid(()->IncomingShareCapture.capture(file().putExtra(Intent.EXTRA_TEXT,new SpannableString("styled")),POLICY,uri->true));
        invalid(()->IncomingShareCapture.capture(file().setData(URI),POLICY,uri->true));
        Intent selected=file();selected.setSelector(new Intent(Intent.ACTION_VIEW));invalid(()->IncomingShareCapture.capture(selected,POLICY,uri->true));
    }
    @Test public void rejectsWritePrefixPersistableAndFdPayloads()throws Exception {
        for(int flag:new int[]{Intent.FLAG_GRANT_WRITE_URI_PERMISSION,Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION})
            invalid(()->IncomingShareCapture.capture(file().addFlags(flag),POLICY,uri->true));
        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
        try{invalid(()->IncomingShareCapture.capture(file().putExtra(Intent.EXTRA_STREAM,pipe[0]),POLICY,uri->true));}
        finally{pipe[0].close();pipe[1].close();}
    }
    @Test public void supportsConfiguredMultipleWithoutSilentTruncation()throws Exception {
        ArrayList<Uri> uris=new ArrayList<>();for(int i=0;i<16;i++)uris.add(Uri.parse("content://fixture.provider/"+i));
        Intent value=new Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(16,IncomingShareCapture.capture(value,POLICY,uri->true).contentUris.size());
        uris.add(Uri.parse("content://fixture.provider/17"));value.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
        invalid(()->IncomingShareCapture.capture(value,POLICY,uri->true));
    }
}
