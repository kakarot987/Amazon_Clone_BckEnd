package amazon.api.commerce;

import java.util.List;

/** Timeline events are sourced from persisted order, shipment and return data. */
public record OrderTimeline(OrderReceipt order, OrderShipmentSummary shipment,
                            OrderReturnSummary returnSummary, List<OrderTimelineEvent> events) {}
