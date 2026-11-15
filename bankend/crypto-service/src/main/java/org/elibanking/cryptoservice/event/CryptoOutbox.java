package org.elibanking.cryptoservice.event;

import org.elibanking.common.web.RequestIdContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class CryptoOutbox {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public CryptoOutbox(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(String topic,String key,Map<String,Object> data) {
        String id=UUID.randomUUID().toString();
        Map<String,Object> envelope=new LinkedHashMap<>(); envelope.put("eventId",id);envelope.put("type",topic);envelope.put("version",1);
        envelope.put("occurredAt",Instant.now().toString());envelope.put("correlationId",RequestIdContext.current().orElse(null));envelope.put("payload",data);
        try { jdbc.update("INSERT INTO crypto_outbox(event_id,topic,message_key,payload) VALUES(?,?,?,?)",id,topic,key,mapper.writeValueAsString(envelope)); }
        catch(Exception e){throw new IllegalStateException("Could not append crypto lifecycle event",e);}
    }
}
