package com.booking;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DemoPaymentsDisabledTests extends BookingApiTestSupport {
    @Test void mockPaymentsAreDisabledByDefaultAndRequireAdmin() throws Exception {
        var id=createdOrder();String path="/demo/orders/"+id+"/confirm-payment";
        code(send("POST",path,Map.of("amount",350),adminToken,null),404,"DEMO_PAYMENTS_DISABLED");
        status(send("POST",path,Map.of("amount",350),token,null),403);
        status(send("POST",path,Map.of("amount",350),null,null),401);
    }
}
