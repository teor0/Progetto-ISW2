package service;

import model.Release;
import model.Ticket;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class ProportionService {

    private static final Logger LOGGER = Logger.getLogger(ProportionService.class.getName());

    /** Use proportion to obtain injected version where there isn't */
    public static void proportion(List<Ticket> ticketList, List<Release> releaseList) throws URISyntaxException, IOException {
        discardInvalidTicket(ticketList, releaseList);
        LOGGER.info("Tickets after validation: " + ticketList.size());
        if (ticketList.isEmpty()) return;
        List<Ticket> ticketsWithIV    = ticketsForProportion(ticketList);
        List<Ticket> ticketsWithoutIV = ticketsWithoutIV(ticketList);
        LOGGER.info("Tickets with IV (for P): " + ticketsWithIV.size()
                + " | without IV (to estimate): " + ticketsWithoutIV.size());
        if (ticketsWithoutIV.isEmpty())
            return;  // nothing to estimate
        // Step 3 – compute P
        if (ticketsWithIV.isEmpty()) {
            LOGGER.warning("No tickets with valid IV available to compute P. "
                    + "Cannot estimate IV for remaining tickets; they will be removed.");
            ticketList.removeAll(ticketsWithoutIV);
            return;
        }
        double p = calculateProportioningCoefficient(ticketsWithIV);
        LOGGER.info(String.format("Proportion coefficient P = %.4f", p));

        applyProportion(ticketsWithoutIV, p, releaseList);

    }

    /** Discard invalid ticket */
    private static void discardInvalidTicket(List<Ticket> ticketsList, List<Release> releaseList){

        Release firstRelease = releaseList.getFirst();

        //Ticket creation date before first release, release date.
        ticketsList.removeIf(ticket -> ticket.getCreationDate() != null
                        && ticket.getCreationDate().isBefore(firstRelease.getReleaseDate()));
        //Missing OV or FV
        ticketsList.removeIf(ticket -> ticket.getOpeningVersion().getName().isEmpty()
                || ticket.getFixVersion().getName().isEmpty());

        //Remove if OV > FV
        ticketsList.removeIf(ticket->ticket.getOpeningVersion().getReleaseNumber()
                > ticket.getFixVersion().getReleaseNumber());

        //IV present but inconsistent with OV or FV
        ticketsList.removeIf(ticket -> {
            if (ticket.getInjectedVersion() == null
                    || ticket.getInjectedVersion().getName().isEmpty())
                return false;
            int iv = ticket.getInjectedVersion().getReleaseNumber();
            return iv > ticket.getOpeningVersion().getReleaseNumber()
                    || iv > ticket.getFixVersion().getReleaseNumber();
        });

    }

    /**
     * Returns the subset of tickets whose IV is <em>already known and valid</em>
     * — i.e. IV is not null, not "", and IV &le; OV and IV &le; FV.
     * These are used to compute the proportion coefficient P.
     */

    private static List<Ticket> ticketsForProportion(List<Ticket> ticketsList){

        List<Ticket> result =  new ArrayList<>(ticketsList);
        // Keep only tickets with a non-null, non-NULL IV
        result.removeIf(ticket->ticket.getInjectedVersion().getName().isEmpty());
        // IV is strictly greater than OV (IV>OV)
        result.removeIf(ticket->ticket.getInjectedVersion().getReleaseNumber()
                > ticket.getOpeningVersion().getReleaseNumber());
        // IV is strictly greater than FV
        result.removeIf(ticket->ticket.getInjectedVersion().getReleaseNumber()
                > ticket.getFixVersion().getReleaseNumber());

        //IV = OV = FV degenerate, cannot compute a meaningful P contribution
        result.removeIf(ticket ->
                ticket.getOpeningVersion().getReleaseNumber()
                        == ticket.getFixVersion().getReleaseNumber()
                        && ticket.getInjectedVersion().getReleaseNumber()
                        == ticket.getFixVersion().getReleaseNumber());


        return result;
    }

    /**
     * Returns the subset of tickets in {@code ticketsList} whose IV is
     * missing (null or "") and therefore needs to be
     * estimated via Proportion.
     */

    private static List<Ticket> ticketsWithoutIV(List<Ticket> ticketsList) {
        List<Ticket> result = new ArrayList<>();
        for (Ticket t : ticketsList) {
            if (t.getInjectedVersion() == null
                    ||t.getInjectedVersion().getName().isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }


    /** Return proportion value for the valid tickets **/
    private static double calculateProportioningCoefficient(List<Ticket> ticketList){
        List<Double> values = new ArrayList<>();
        for (Ticket t : ticketList) {
            int iv = t.getInjectedVersion().getReleaseNumber();
            int ov = t.getOpeningVersion().getReleaseNumber();
            int fv = t.getFixVersion().getReleaseNumber();

            double pi = (fv == ov) ? (fv - iv) : (double) (fv - iv) / (fv - ov);

            values.add(pi);
        }
        return proportionTotal(values);

    }

    /** Return proportion as average of all the values */
    private static double proportionTotal(List<Double> values) {
        double sum = 0.0;
        for (double v : values)
            sum += v;
        return sum / values.size();
    }


    /**
     * Applies the proportion formula to every ticket in {@code tickets},
     * setting its IV in-place.
     */
    private static void applyProportion(List<Ticket> tickets, double proportion, List<Release> releaseList) {
        for (Ticket t : tickets) {
            setInjected(t, proportion, releaseList);
        }
    }


    /**
     * Estimates and sets the IV for a single ticket.
     *
     * <pre>
     *   if FV != OV:  IV = FV - (FV - OV) * P
     *   else:         IV = FV - P
     * </pre>
     *
     * If the computed index is ≤ 1 the very first release is used.
     * If no release with the computed index exists the nearest earlier
     * release is chosen as a safe fallback.
     */
    private static void setInjected(Ticket ticket, double proportion, List<Release> releaseList) {

        // Guard: only act if IV is genuinely missing
        if (ticket.getInjectedVersion() != null
                && !(ticket.getInjectedVersion().getName().isEmpty())) {
            return;
        }

        int fv = ticket.getFixVersion().getReleaseNumber();
        int ov = ticket.getOpeningVersion().getReleaseNumber();

        int predictedIv = (fv != ov) ? (int) Math.round(fv - (fv - ov) * proportion) : (int) Math.round(fv - proportion);

        // Clamp to at least 1
        if (predictedIv < 1)
            predictedIv = 1;

        // Find the matching (or nearest earlier) release
        Release chosen = releaseList.getFirst();   // safest fallback: first release
        for (Release r : releaseList) {
            // we don't use every
            if (r.getReleaseNumber() <= predictedIv) {
                chosen = r;
            } else {
                break;  // list is sorted ascending, so we can stop early
            }
        }
        ticket.setInjectedVersion(chosen);
    }




}
