package amazon.microservices.composite.product.outbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

/** Durable publisher journal. A sent record is retained for audit and replay safety. */
@Document("composite_outbox")
@CompoundIndexes({
    @CompoundIndex(name="outbox_dispatch_idx", def="{'state':1,'createdAt':1,'id':1}"),
    @CompoundIndex(name="outbox_reconcile_idx", def="{'partitionKey':1,'createdAt':-1,'_id':-1}")
})
public class OutboxEntry {
    public static final String PENDING = "PENDING";
    public static final String IN_FLIGHT = "IN_FLIGHT";
    public static final String PUBLISHED = "PUBLISHED";

    @Id private String id;
    private String bindingName;
    private String payloadJson;
    private Integer partitionKey;
    private Instant createdAt;
    private Instant nextAttemptAt;
    private Instant leaseUntil;
    private String leaseId;
    private String state;
    private int attempts;
    private Instant publishedAt;

    public OutboxEntry() {}
    public OutboxEntry(String id, String bindingName, String payloadJson,
                       Integer partitionKey, Instant createdAt) {
        this.id=id;
        this.bindingName=bindingName;
        this.payloadJson=payloadJson;
        this.partitionKey=partitionKey;
        this.createdAt=createdAt;
        this.nextAttemptAt=createdAt;
        this.state=PENDING;
    }
    public String getId(){return id;}
    public String getBindingName(){return bindingName;}
    public String getPayloadJson(){return payloadJson;}
    public Integer getPartitionKey(){return partitionKey;}
    public Instant getCreatedAt(){return createdAt;}
    public Instant getNextAttemptAt(){return nextAttemptAt;}
    public Instant getLeaseUntil(){return leaseUntil;}
    public String getLeaseId(){return leaseId;}
    public String getState(){return state;}
    public int getAttempts(){return attempts;}
    public Instant getPublishedAt(){return publishedAt;}
    public void setId(String id){this.id=id;}
    public void setBindingName(String v){bindingName=v;}
    public void setPayloadJson(String v){payloadJson=v;}
    public void setPartitionKey(Integer v){partitionKey=v;}
    public void setCreatedAt(Instant v){createdAt=v;}
    public void setNextAttemptAt(Instant v){nextAttemptAt=v;}
    public void setLeaseUntil(Instant v){leaseUntil=v;}
    public void setLeaseId(String v){leaseId=v;}
    public void setState(String v){state=v;}
    public void setAttempts(int v){attempts=v;}
    public void setPublishedAt(Instant v){publishedAt=v;}
}
