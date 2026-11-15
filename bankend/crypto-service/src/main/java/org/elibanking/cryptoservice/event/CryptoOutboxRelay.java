package org.elibanking.cryptoservice.event;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.elibanking.common.events.KafkaEventHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class CryptoOutboxRelay {
    private static final Logger LOG=LoggerFactory.getLogger(CryptoOutboxRelay.class);
    private final JdbcTemplate jdbc; private final KafkaTemplate<String,Object> kafka; private final ObjectMapper mapper; private final TransactionTemplate tx;
    public CryptoOutboxRelay(JdbcTemplate jdbc,KafkaTemplate<String,Object> kafka,ObjectMapper mapper,TransactionTemplate tx){this.jdbc=jdbc;this.kafka=kafka;this.mapper=mapper;this.tx=tx;}
    @Scheduled(fixedDelayString="${crypto.outbox-relay-delay-ms:1000}")
    public void relay(){tx.executeWithoutResult(status->{List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,topic,message_key,payload FROM crypto_outbox WHERE sent_at IS NULL ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED");
        for(Map<String,Object> row:rows){try{Object event=mapper.readValue(row.get("payload").toString(),Object.class);ProducerRecord<String,Object> record=new ProducerRecord<>((String)row.get("topic"),(String)row.get("message_key"),event);
            if(event instanceof Map<?,?> map)KafkaEventHeaders.apply(map,(name,value)->record.headers().add(name,value));kafka.send(record).get(10,TimeUnit.SECONDS);jdbc.update("UPDATE crypto_outbox SET sent_at=CURRENT_TIMESTAMP(6) WHERE id=?",row.get("id"));}
            catch(Exception e){LOG.warn("Crypto outbox publication will retry outboxId={} errorType={}",row.get("id"),e.getClass().getSimpleName());break;}}
    });}
}
