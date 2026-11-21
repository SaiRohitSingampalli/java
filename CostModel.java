/*
 * Lab:       A build-versus-buy cost model that counts people, with the sensitivity table that decides it
 * Chapters:  39 (exercise 4: a cost comparison), 38 §4 (options E and F)
 * Run:       java CostModel.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   none: the inputs are illustrative assumptions. Replace them with your own figures and a real quote.
 * Output:    deterministic arithmetic, month by month for 36 months.
 */
public class CostModel {

    // ---- Illustrative inputs: change these, not the code below ----
    static final double ENGINEER_YEAR = 180_000;          // fully loaded cost of one engineer for a year, in dollars
    static final double DELIVERIES_PER_DAY = 39e6;        // today's volume (Chapter 38 §3.6)
    static final double GROWTH = 0.40;                    // volume growth per year
    // Build (option E): 4 engineers for 5 months, then a team of 1.5 and infrastructure that grows with volume
    static final int BUILD_MONTHS = 5;
    static final double BUILD_TEAM = 4, RUN_TEAM = 1.5, INFRA_PER_MONTH = 8_000;
    // Buy (option F): 3 engineers for 3 months (integration, security review), then 0.5 engineers plus the vendor
    static final int INTEGRATION_MONTHS = 3;
    static final double INTEGRATION_TEAM = 3, VENDOR_TEAM = 0.5, PRICE_PER_MILLION = 15;

    record Years(double[] build, double[] buy) {
        double buildTotal() { return build[0] + build[1] + build[2]; }
        double buyTotal() { return buy[0] + buy[1] + buy[2]; }
    }

    static Years model(double pricePerMillion, double growth) {
        double[] build = new double[3], buy = new double[3];
        double month = ENGINEER_YEAR / 12;
        for (int m = 1; m <= 36; m++) {
            int year = (m - 1) / 12;
            double volume = Math.pow(1 + growth, (m - 1) / 12.0);   // relative to today
            build[year] += m <= BUILD_MONTHS ? BUILD_TEAM * month : RUN_TEAM * month + INFRA_PER_MONTH * volume;
            buy[year] += m <= INTEGRATION_MONTHS ? INTEGRATION_TEAM * month
                    : VENDOR_TEAM * month + pricePerMillion * DELIVERIES_PER_DAY * 365.0 / 12 * volume / 1e6;
        }
        return new Years(build, buy);
    }

    public static void main(String[] args) {
        System.out.println("Build vs buy for webhook delivery over 3 years. ILLUSTRATIVE inputs: edit them at the top of the file.");
        System.out.printf("  an engineer-year costs $%,.0f; %.0f M deliveries a day today, growing %.0f%% a year%n",
                          ENGINEER_YEAR, DELIVERIES_PER_DAY / 1e6, GROWTH * 100);
        System.out.printf("  build: %.0f engineers for %d months, then %.1f engineers and $%,.0f a month of infrastructure (grows with volume)%n",
                          BUILD_TEAM, BUILD_MONTHS, RUN_TEAM, INFRA_PER_MONTH);
        System.out.printf("  buy:   %.0f engineers for %d months (integration, security review), then %.1f engineers and $%.0f per million deliveries%n",
                          INTEGRATION_TEAM, INTEGRATION_MONTHS, VENDOR_TEAM, PRICE_PER_MILLION);

        Years base = model(PRICE_PER_MILLION, GROWTH);
        System.out.println();
        System.out.println("1. Cost by year, $ thousands");
        System.out.printf("   %-24s %8s %8s %8s %9s%n", "", "year 1", "year 2", "year 3", "total");
        row("build", base.build(), base.buildTotal());
        row("buy", base.buy(), base.buyTotal());
        double[] diff = {base.buy()[0] - base.build()[0], base.buy()[1] - base.build()[1], base.buy()[2] - base.build()[2]};
        row("buy minus build", diff, base.buyTotal() - base.buildTotal());

        double[] prices = {5, 10, 15, 20, 30}, growths = {0, 0.2, 0.4, 0.8};
        System.out.println();
        System.out.println("2. Buy minus build over 3 years, $ thousands (negative: buying is cheaper)");
        System.out.printf("   %-24s %s%n", "vendor price per", "volume growth per year");
        System.out.printf("   %-24s", "million deliveries");
        for (double g : growths) System.out.printf(" %8s", String.format("%.0f%%", g * 100));
        System.out.println();
        for (double p : prices) {
            System.out.printf("   %-24s", String.format("$%.0f", p));
            for (double g : growths) {
                Years y = model(p, g);
                System.out.printf(" %8s", String.format("%+,.0f", (y.buyTotal() - y.buildTotal()) / 1000));
            }
            System.out.println();
        }

        System.out.println();
        System.out.println("3. The vendor price at which the two options cost the same over 3 years");
        for (double g : growths) {
            // buy minus build is linear in the price, so two points give the break-even exactly
            Years at0 = model(0, g), at1 = model(1, g);
            double d0 = at0.buyTotal() - at0.buildTotal(), slope = (at1.buyTotal() - at1.buildTotal()) - d0;
            System.out.printf("   at %3.0f%% growth: $%.2f per million deliveries%n", g * 100, -d0 / slope);
        }
    }

    static void row(String label, double[] years, double total) {
        System.out.printf("   %-24s %,8.0f %,8.0f %,8.0f %,9.0f%n", label, years[0] / 1000, years[1] / 1000, years[2] / 1000, total / 1000);
    }
}
