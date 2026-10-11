package com.jarvys.factory.contract;

import java.io.IOException;

/** Exact read-only audit of AAPT-compiled API26 adaptive launcher resources. No project XML is accepted. */
public final class AdaptiveIconAudit {
    private AdaptiveIconAudit() {}
    public static void verify(byte[] compiledXml, int backgroundId, int foregroundId) throws IOException {
        if ((backgroundId >>> 24)!=0x7f || (foregroundId >>> 24)!=0x7f || backgroundId==foregroundId)
            throw new IOException("Invalid adaptive layer resource identities");
        ManifestAudit.Document document=ManifestAudit.read(compiledXml);
        if (document.nodes.size()!=3) throw new IOException("Adaptive icon must contain exactly two layers");
        ManifestAudit.Node root=document.nodes.get(0);
        if (!root.path.equals("adaptive-icon") || root.parentIndex!=-1 || !root.attributes.isEmpty())
            throw new IOException("Unexpected adaptive icon root");
        String[] names={"background","foreground"}; int[] ids={backgroundId,foregroundId};
        for (int i=0;i<2;i++) {
            ManifestAudit.Node layer=document.nodes.get(i+1);
            ManifestAudit.Attribute drawable=layer.attributes.get(ManifestPlan.ANDROID+"|drawable");
            if (!layer.path.equals("adaptive-icon/"+names[i]) || layer.parentIndex!=0 || layer.attributes.size()!=1 ||
                drawable==null || drawable.resourceId!=0x01010199 || drawable.type!=1 || !Integer.valueOf(ids[i]).equals(drawable.value))
                throw new IOException("Adaptive icon layer differs from compiled binding: "+names[i]);
        }
    }
}
