package com.jarvys.factory.runtime;
import org.json.JSONObject;
/** Additional explicit retention control, separate from schedule/cancel. */
public final class NotificationScheduleControl {
    public static final class Forget {
        public final String scheduleId;
        public final boolean acknowledgeDeliveryUnknown;
        Forget(String id,boolean acknowledge) { scheduleId=id; acknowledgeDeliveryUnknown=acknowledge; }
    }
    public static Forget parseForget(String json) throws FactoryException {
        JSONObject value=StrictJson.object(json,384);
        FactoryConfig.exactKeys(value,"scheduleId","acknowledgeDeliveryUnknown");
        Object id=value.opt("scheduleId"), ack=value.opt("acknowledgeDeliveryUnknown");
        if (!(id instanceof String) || !((String)id).matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}") || (value.has("acknowledgeDeliveryUnknown") && !(ack instanceof Boolean)))
            throw new FactoryException("INVALID_ARGUMENT","Invalid scheduled notification retention control.");
        return new Forget((String)id,Boolean.TRUE.equals(ack));
    }
    public static final class Generation {
        public final String generationId;public final boolean acknowledgeDeliveryUnknown;
        Generation(String id,boolean acknowledge){generationId=id;acknowledgeDeliveryUnknown=acknowledge;}
    }
    public static String parseGenerationStatus(String json)throws FactoryException {
        JSONObject value=StrictJson.object(json,256);FactoryConfig.exactKeys(value,"generationId");
        Object id=value.opt("generationId");if(!(id instanceof String)||!NotificationActionEvents.validToken((String)id))throw invalid();return (String)id;
    }
    public static Generation parseForgetGeneration(String json)throws FactoryException {
        JSONObject value=StrictJson.object(json,384);FactoryConfig.exactKeys(value,"generationId","acknowledgeDeliveryUnknown");
        Object id=value.opt("generationId"),ack=value.opt("acknowledgeDeliveryUnknown");
        if(!(id instanceof String)||!NotificationActionEvents.validToken((String)id)||(value.has("acknowledgeDeliveryUnknown")&&!(ack instanceof Boolean)))throw invalid();
        return new Generation((String)id,Boolean.TRUE.equals(ack));
    }
    private static FactoryException invalid(){return new FactoryException("INVALID_ARGUMENT","Invalid notification generation control.");}
    private NotificationScheduleControl() { }
}
