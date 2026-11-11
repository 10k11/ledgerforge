package dev.ledgerforge.common.web;

/** Normalises client pagination input: page &gt;= 0, 1 &lt;= size &lt;= 100. */
public record PageParams(int page, int size) {

    public static final int MAX_SIZE = 100;

    public static PageParams of(Integer page, Integer size) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.min(MAX_SIZE, Math.max(1, size));
        return new PageParams(p, s);
    }

    public long offset() {
        return (long) page * size;
    }
}
