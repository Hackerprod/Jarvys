package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Android-only input boundary. Does not query providers, load content, coerce text or persist grants. */
public final class IncomingShareCapture {
    private IncomingShareCapture() {}
    public interface Grants { boolean canRead(Uri uri); }
    public static IncomingSharePayload capture(Activity activity, Intent intent, IncomingSharePayload.Policy policy) throws FactoryException {
        return capture(intent,policy,uri -> activity.checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED);
    }
    public static IncomingSharePayload capture(Intent intent,IncomingSharePayload.Policy policy,Grants grants) throws FactoryException {
        try {
            if(intent==null || grants==null || policy==null || intent.getData()!=null || intent.getSelector()!=null || intent.hasFileDescriptors()
                    || (intent.getFlags() & (Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))!=0)
                throw IncomingSharePayload.invalid();
            String action=intent.getAction();
            if(!IncomingSharePayload.SEND.equals(action) && !IncomingSharePayload.SEND_MULTIPLE.equals(action)) throw IncomingSharePayload.invalid();
            Bundle extras=intent.getExtras(); Object text=null,subject=null,rawStreams=null;
            boolean streamExtra=false;
            if(extras!=null) {
                // Framework-only Parcelable types (Uri) are the only permitted non-String values.
                extras.setClassLoader(null);
                Set<String> allowed=new HashSet<>(Arrays.asList(Intent.EXTRA_TEXT,Intent.EXTRA_SUBJECT,Intent.EXTRA_STREAM));
                if(extras.hasFileDescriptors() || !allowed.containsAll(extras.keySet())) throw IncomingSharePayload.invalid();
                text=extras.get(Intent.EXTRA_TEXT); subject=extras.get(Intent.EXTRA_SUBJECT);
                streamExtra=extras.containsKey(Intent.EXTRA_STREAM); rawStreams=extras.get(Intent.EXTRA_STREAM);
            }
            // Validate text before inspecting clip items and before retaining any source values.
            String plain=IncomingSharePayload.text(text,policy.limits.textBytes);
            IncomingSharePayload.text(subject,IncomingSharePayload.MAX_SUBJECT_BYTES);
            ArrayList<String> extraUris=new ArrayList<>();
            if(streamExtra) {
                if(IncomingSharePayload.SEND.equals(action)) addUri(extraUris,rawStreams,policy.limits.attachmentsPerEnvelope);
                else {
                    if(!(rawStreams instanceof ArrayList)) throw IncomingSharePayload.invalid();
                    List<?> values=(List<?>)rawStreams;
                    if(values.isEmpty() || values.size()>policy.limits.attachmentsPerEnvelope) throw IncomingSharePayload.invalid();
                    for(Object value:values) addUri(extraUris,value,policy.limits.attachmentsPerEnvelope);
                }
            }
            ClipData clip=intent.getClipData(); ArrayList<String> clipUris=new ArrayList<>();
            if(clip!=null) {
                int count=clip.getItemCount();
                if(count<1 || count>policy.limits.attachmentsPerEnvelope) throw IncomingSharePayload.invalid();
                for(int i=0;i<count;i++) {
                    ClipData.Item item=clip.getItemAt(i);
                    if(item==null || item.getIntent()!=null || item.getHtmlText()!=null) throw IncomingSharePayload.invalid();
                    Object clipText=item.getText();
                    if(clipText!=null && (!(clipText instanceof String) || !clipText.equals(plain))) throw IncomingSharePayload.invalid();
                    if(item.getUri()!=null) addUri(clipUris,item.getUri(),policy.limits.attachmentsPerEnvelope);
                    else if(clipText==null) throw IncomingSharePayload.invalid();
                }
            }
            // Do not union inconsistent attachment lists or let ClipData smuggle extra authority.
            if(!extraUris.isEmpty() && !clipUris.isEmpty() && !extraUris.equals(clipUris)) throw IncomingSharePayload.invalid();
            List<String> uris=extraUris.isEmpty()?clipUris:extraUris;
            IncomingSharePayload payload=IncomingSharePayload.parse(policy,action,intent.getType(),plain,subject,uris);
            if(!uris.isEmpty() && (intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0) throw IncomingSharePayload.invalid();
            for(String value:uris) if(!grants.canRead(Uri.parse(value))) throw new FactoryException("PERMISSION_DENIED","Incoming content has no current Android read grant.");
            return payload;
        } catch(FactoryException error) { throw error; }
        catch(RuntimeException malformed) { throw IncomingSharePayload.invalid(); }
    }
    private static void addUri(List<String> result,Object raw,int limit) throws FactoryException {
        if(!(raw instanceof Uri) || result.size()>=limit) throw IncomingSharePayload.invalid();
        String value=raw.toString();
        if(!IncomingSharePayload.contentUri(value) || result.contains(value)) throw IncomingSharePayload.invalid();
        result.add(value);
    }
}
