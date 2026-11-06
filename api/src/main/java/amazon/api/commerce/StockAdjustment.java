package amazon.api.commerce;

/**
 * Privileged inventory correction. A delta of -3 removes three available
 * units; +3 restores three units. Not a durable order reservation.
 */
public class StockAdjustment {
    private int delta;

    public StockAdjustment() {}

    public StockAdjustment(int delta) { this.delta = delta; }

    public int getDelta() { return delta; }
    public void setDelta(int delta) { this.delta = delta; }
}
