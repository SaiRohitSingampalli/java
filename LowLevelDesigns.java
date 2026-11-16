import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

public class LowLevelDesigns {
    // ---------- LRU from scratch
    static final class LruCache<K, V> {
        private static final class Node<K, V> {
            final K key; V value; Node<K, V> prev, next;
            Node(K key, V value) { this.key = key; this.value = value; }
        }
        private final int capacity;
        private final Map<K, Node<K, V>> index;
        private final Node<K, V> head = new Node<>(null, null), tail = new Node<>(null, null);
        LruCache(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
            this.capacity = capacity;
            this.index = HashMap.newHashMap(capacity);
            head.next = tail; tail.prev = head;
        }
        V get(K key) {
            Node<K, V> n = index.get(key);
            if (n == null) return null;
            moveToFront(n);
            return n.value;
        }
        void put(K key, V value) {
            Node<K, V> n = index.get(key);
            if (n != null) { n.value = value; moveToFront(n); return; }
            if (index.size() == capacity) {
                Node<K, V> lru = tail.prev;
                unlink(lru);
                index.remove(lru.key);
            }
            n = new Node<>(key, value);
            index.put(key, n);
            linkAfterHead(n);
        }
        List<K> keysMostRecentFirst() { List<K> ks = new ArrayList<>(); for (Node<K, V> n = head.next; n != tail; n = n.next) ks.add(n.key); return ks; }
        private void moveToFront(Node<K, V> n) { unlink(n); linkAfterHead(n); }
        private void unlink(Node<K, V> n) { n.prev.next = n.next; n.next.prev = n.prev; }
        private void linkAfterHead(Node<K, V> n) { n.next = head.next; n.prev = head; head.next.prev = n; head.next = n; }
    }
    // ---------- TTL cache
    static final class TtlCache<K, V> {
        private record Entry<V>(V value, long expiresAtNanos) {}
        private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();
        private final LongSupplier nanoClock;
        TtlCache(LongSupplier nanoClock) { this.nanoClock = nanoClock; }
        void put(K key, V value, Duration ttl) { map.put(key, new Entry<>(value, nanoClock.getAsLong() + ttl.toNanos())); }
        Optional<V> get(K key) {
            Entry<V> e = map.get(key);
            if (e == null) return Optional.empty();
            if (nanoClock.getAsLong() - e.expiresAtNanos() >= 0) {
                map.remove(key, e);
                return Optional.empty();
            }
            return Optional.of(e.value());
        }
        int sweep() {
            long now = nanoClock.getAsLong();
            int before = map.size();
            map.entrySet().removeIf(en -> now - en.getValue().expiresAtNanos() >= 0);
            return before - map.size();
        }
        int size() { return map.size(); }
    }
    // ---------- parking lot
    enum VehicleSize { MOTORCYCLE, CAR, TRUCK }
    enum SpotSize { SMALL, MEDIUM, LARGE }
    record Vehicle(String plate, VehicleSize size) {}
    record Spot(String id, SpotSize size) {}
    record Ticket(String id, Vehicle vehicle, Spot spot, Instant entry) {}
    interface PricingStrategy { long priceCents(Ticket ticket, Instant exit); }
    record HourlyPricing(Map<SpotSize, Long> centsPerHour, Duration gracePeriod) implements PricingStrategy {
        public long priceCents(Ticket t, Instant exit) {
            Duration stay = Duration.between(t.entry(), exit);
            if (stay.compareTo(gracePeriod) <= 0) return 0;
            long startedHours = (stay.toSeconds() + 3599) / 3600;
            return startedHours * centsPerHour.get(t.spot().size());
        }
    }
    static final class ParkingLot {
        private final Map<SpotSize, Queue<Spot>> free = new EnumMap<>(SpotSize.class);
        private final Map<String, Ticket> active = new ConcurrentHashMap<>();
        private final PricingStrategy pricing;
        private final Clock clock;
        ParkingLot(Collection<Spot> spots, PricingStrategy pricing, Clock clock) {
            for (SpotSize s : SpotSize.values()) free.put(s, new ConcurrentLinkedQueue<>());
            for (Spot s : spots) free.get(s.size()).offer(s);
            this.pricing = pricing; this.clock = clock;
        }
        Optional<Ticket> park(Vehicle v) {
            for (SpotSize size : fits(v.size())) {
                Spot spot = free.get(size).poll();
                if (spot != null) {
                    Ticket t = new Ticket(UUID.randomUUID().toString(), v, spot, clock.instant());
                    active.put(t.id(), t);
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
        long exit(String ticketId) {
            Ticket t = active.remove(ticketId);
            if (t == null) throw new IllegalArgumentException("unknown or already closed ticket: " + ticketId);
            free.get(t.spot().size()).offer(t.spot());
            return pricing.priceCents(t, clock.instant());
        }
        private static List<SpotSize> fits(VehicleSize v) {
            return switch (v) {
                case MOTORCYCLE -> List.of(SpotSize.SMALL, SpotSize.MEDIUM, SpotSize.LARGE);
                case CAR -> List.of(SpotSize.MEDIUM, SpotSize.LARGE);
                case TRUCK -> List.of(SpotSize.LARGE);
            };
        }
    }
    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant start) { now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
    }
    // ---------- elevator (LOOK)
    static final class Elevator {
        enum Direction { UP, DOWN, IDLE }
        private final NavigableSet<Integer> upStops = new TreeSet<>(), downStops = new TreeSet<>();
        private int floor;
        private Direction direction = Direction.IDLE;
        synchronized void request(int target) {
            if (target > floor) upStops.add(target);
            else if (target < floor) downStops.add(target);
        }
        synchronized OptionalInt step() {
            if (direction == Direction.IDLE) {
                if (!upStops.isEmpty()) direction = Direction.UP;
                else if (!downStops.isEmpty()) direction = Direction.DOWN;
                else return OptionalInt.empty();
            }
            floor += direction == Direction.UP ? 1 : -1;
            boolean stopHere = (direction == Direction.UP ? upStops : downStops).remove(floor);
            if (direction == Direction.UP && upStops.isEmpty()) direction = downStops.isEmpty() ? Direction.IDLE : Direction.DOWN;
            else if (direction == Direction.DOWN && downStops.isEmpty()) direction = upStops.isEmpty() ? Direction.IDLE : Direction.UP;
            return stopHere ? OptionalInt.of(floor) : OptionalInt.empty();
        }
        synchronized boolean idle() { return direction == Direction.IDLE && upStops.isEmpty() && downStops.isEmpty(); }
        synchronized int floor() { return floor; }
    }
    // ---------- broker
    static final class InMemoryBroker implements AutoCloseable {
        interface Subscriber { void onMessage(String topic, String payload) throws Exception; }
        final class Subscription {
            private final String topic;
            private final Subscriber subscriber;
            private final BlockingQueue<String> buffer;
            private final LongAdder dropped = new LongAdder(), failed = new LongAdder();
            private volatile Future<?> worker;
            Subscription(String topic, Subscriber subscriber, int bufferSize) {
                this.topic = topic; this.subscriber = subscriber; this.buffer = new ArrayBlockingQueue<>(bufferSize);
            }
            private void deliverLoop() {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        String msg = buffer.take();
                        try { subscriber.onMessage(topic, msg); }
                        catch (Exception e) { failed.increment(); }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            long dropped() { return dropped.sum(); }
            void cancel() { topics.getOrDefault(topic, List.of()).remove(this); if (worker != null) worker.cancel(true); }
        }
        private final Map<String, List<Subscription>> topics = new ConcurrentHashMap<>();
        private final ExecutorService deliveryThreads = Executors.newVirtualThreadPerTaskExecutor();
        Subscription subscribe(String topic, Subscriber subscriber, int bufferSize) {
            Subscription sub = new Subscription(topic, subscriber, bufferSize);
            topics.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(sub);
            sub.worker = deliveryThreads.submit(sub::deliverLoop);
            return sub;
        }
        int publish(String topic, String payload) {
            int accepted = 0;
            for (Subscription sub : topics.getOrDefault(topic, List.of())) {
                if (sub.buffer.offer(payload)) accepted++;
                else sub.dropped.increment();
            }
            return accepted;
        }
        @Override public void close() { deliveryThreads.shutdownNow(); }
    }

    public static void main(String[] args) throws Exception {
        LruCache<String, Integer> lru = new LruCache<>(3);
        lru.put("a", 1); lru.put("b", 2); lru.put("c", 3);
        lru.get("a");
        lru.put("d", 4);
        System.out.println("LRU after get(a), put(d): " + lru.keysMostRecentFirst() + "  get(b)=" + lru.get("b"));

        AtomicLong fakeNanos = new AtomicLong();
        TtlCache<String, String> ttl = new TtlCache<>(fakeNanos::get);
        ttl.put("session:1", "alice", Duration.ofSeconds(30));
        ttl.put("session:2", "bob", Duration.ofSeconds(90));
        fakeNanos.addAndGet(Duration.ofSeconds(31).toNanos());
        System.out.println("TTL t=31s: session:1=" + ttl.get("session:1") + " session:2=" + ttl.get("session:2"));
        fakeNanos.addAndGet(Duration.ofSeconds(60).toNanos());
        System.out.println("TTL t=91s: swept=" + ttl.sweep() + " size=" + ttl.size());

        MutableClock clock = new MutableClock(Instant.parse("2026-11-15T09:00:00Z"));
        List<Spot> spots = new ArrayList<>();
        for (int i = 0; i < 300; i++) spots.add(new Spot("M" + i, SpotSize.MEDIUM));
        for (int i = 0; i < 200; i++) spots.add(new Spot("L" + i, SpotSize.LARGE));
        ParkingLot lot = new ParkingLot(spots, new HourlyPricing(Map.of(SpotSize.SMALL, 200L, SpotSize.MEDIUM, 400L, SpotSize.LARGE, 800L), Duration.ofMinutes(15)), clock);
        Set<String> spotIds = ConcurrentHashMap.newKeySet();
        AtomicInteger issued = new AtomicInteger(), turnedAway = new AtomicInteger(), duplicates = new AtomicInteger();
        try (ExecutorService ex = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 1000; i++) {
                int n = i;
                ex.submit(() -> lot.park(new Vehicle("KA-" + n, VehicleSize.CAR)).ifPresentOrElse(
                        t -> { issued.incrementAndGet(); if (!spotIds.add(t.spot().id())) duplicates.incrementAndGet(); },
                        turnedAway::incrementAndGet));
            }
        }
        System.out.println("parking: issued=" + issued + " turnedAway=" + turnedAway + " duplicateSpots=" + duplicates);
        ParkingLot small = new ParkingLot(List.of(new Spot("M1", SpotSize.MEDIUM)), new HourlyPricing(Map.of(SpotSize.SMALL, 200L, SpotSize.MEDIUM, 400L, SpotSize.LARGE, 800L), Duration.ofMinutes(15)), clock);
        Ticket t = small.park(new Vehicle("KA-01", VehicleSize.CAR)).orElseThrow();
        clock.advance(Duration.ofMinutes(61));
        System.out.println("61 minutes in a MEDIUM spot -> " + small.exit(t.id()) + " cents");
        try { small.exit(t.id()); } catch (IllegalArgumentException e) { System.out.println("second exit -> " + e.getMessage().replace(t.id(), "<id>")); }

        Elevator el = new Elevator();
        el.request(5); el.request(2); el.request(8);
        List<Integer> stops = new ArrayList<>();
        while (!el.idle()) el.step().ifPresent(stops::add);
        el.request(3); el.request(10);
        while (!el.idle()) el.step().ifPresent(stops::add);
        System.out.println("elevator stops: " + stops);

        try (InMemoryBroker broker = new InMemoryBroker()) {
            LongAdder fastGot = new LongAdder(), slowGot = new LongAdder();
            broker.subscribe("orders", (topic, p) -> fastGot.increment(), 2048);
            var slowSub = broker.subscribe("orders", (topic, p) -> { Thread.sleep(5); slowGot.increment(); }, 10);
            for (int i = 0; i < 1000; i++) broker.publish("orders", "order-" + i);
            Thread.sleep(500);
            System.out.println("broker: fast received=" + fastGot.sum() + "  slow received=" + slowGot.sum() + "  slow dropped=" + slowSub.dropped());
        }
    }
}
