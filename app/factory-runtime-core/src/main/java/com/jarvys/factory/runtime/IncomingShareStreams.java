package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Open only after Claim.prepare has checked unified policy and reserved all DocumentHandles slots. */
public final class IncomingShareStreams {
    private IncomingShareStreams() {}
    public static IncomingShareInbox.Opener opener(Activity activity,IncomingShareInbox.Access access,CancellationSignal signal) {
        if(activity==null || access==null || signal==null)throw new IllegalArgumentException();
        return value -> {
            if(!IncomingSharePayload.contentUri(value))throw IncomingSharePayload.invalid();
            Uri uri=Uri.parse(value);
            allowed(activity,uri,access);
            ParcelFileDescriptor descriptor=activity.getContentResolver().openFileDescriptor(uri,"r",signal);
            if(descriptor==null)throw new IOException("Incoming provider returned no stream");
            // Transfer immediately. A failing post-open ownership check must use DocumentHandles'
            // tracked cleanup, never free the reservation while descriptor closure is uncertain.
            return new FilterInputStream(new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                @Override public int read() throws IOException { allowed(activity,uri,access);return in.read(); }
                @Override public int read(byte[] data,int offset,int count) throws IOException { allowed(activity,uri,access);return in.read(data,offset,count); }
                @Override public long skip(long count) throws IOException { allowed(activity,uri,access);return in.skip(count); }
            };
        };
    }
    private static void allowed(Activity activity,Uri uri,IncomingShareInbox.Access access) throws IOException {
        try {
            access.check();
            if(activity.checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Incoming Android URI read grant ended");
            access.check();
        } catch(RuntimeException denied) { throw new IOException("Incoming share permission ended",denied); }
    }
}
