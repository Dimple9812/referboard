package com.referboard.billing.razorpay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.referboard.billing.config.BillingProperties;
import com.referboard.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/** The three Razorpay REST calls billing needs: create an order, fetch a payment, capture it. Basic auth. */
@Component
public class RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Order(String id, long amount, String currency, String receipt, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(String id, @JsonProperty("order_id") String orderId, long amount, String currency,
                          String status, String method) {
        public boolean capturedOrAuthorized() {
            return "captured".equals(status) || "authorized".equals(status);
        }
    }

    private final BillingProperties props;
    private final RestClient http;

    public RazorpayClient(BillingProperties props, RestClient.Builder builder) {
        this.props = props;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(5_000);
        timeouts.setReadTimeout(15_000);
        BillingProperties.Razorpay r = props.razorpay();
        this.http = builder.clone().baseUrl(r.apiBaseUrl()).requestFactory(timeouts)
                .defaultHeaders(h -> {
                    if (r.configured()) {
                        h.setBasicAuth(r.keyId(), r.keySecret());
                    }
                })
                .build();
    }

    public Order createOrder(long amountPaise, String currency, String receipt, Map<String, String> notes) {
        return call(() -> http.post().uri("/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amountPaise, "currency", currency, "receipt", receipt, "notes", notes))
                .retrieve().body(Order.class), "Razorpay couldn't start the payment. You haven't been charged; please try again.");
    }

    public Payment payment(String paymentId) {
        return call(() -> http.get().uri("/v1/payments/{id}", paymentId).retrieve().body(Payment.class),
                AFTER_PAYMENT);
    }

    /** Only needed when the Razorpay account doesn't auto-capture. */
    public Payment capture(String paymentId, long amountPaise, String currency) {
        return call(() -> http.post().uri("/v1/payments/{id}/capture", paymentId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amountPaise, "currency", currency)).retrieve().body(Payment.class),
                AFTER_PAYMENT);
    }

    /** After the customer has paid: their money is safe and the webhook will finish the job. */
    static final String AFTER_PAYMENT = "We couldn't confirm your payment with Razorpay yet. If you were charged, "
            + "Pro switches on automatically within a few minutes.";

    private <T> T call(java.util.function.Supplier<T> request, String message) {
        if (!props.razorpay().configured()) {
            throw unavailable();
        }
        try {
            return request.get();
        } catch (RestClientException e) {
            log.warn("Razorpay call failed: {}", e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", message);
        }
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_UNAVAILABLE",
                "Payments aren't set up on this ReferBoard yet.");
    }
}
