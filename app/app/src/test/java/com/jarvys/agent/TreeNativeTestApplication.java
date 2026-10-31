package com.jarvys.agent;

import android.app.Application;
import com.jarvys.agent.connectors.ConnectionAccess;
import com.jarvys.agent.connectors.ConnectionAccessProvider;

/** Native runtime host fixture: genuine application-owned local connector ledger, no startup work. */
public final class TreeNativeTestApplication extends Application implements ConnectionAccessProvider {
    private ConnectionAccess connections;
    @Override public synchronized ConnectionAccess connectionAccess() {
        if (connections == null) connections = ConnectionAccess.createForApplication(this);
        return connections;
    }
}
