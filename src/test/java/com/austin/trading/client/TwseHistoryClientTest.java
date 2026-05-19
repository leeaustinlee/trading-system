package com.austin.trading.client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TwseHistoryClientTest {

    @Test
    void taiexHistoryUsesCurrentTwseIndicesEndpoint() throws Exception {
        Field path = TwseHistoryClient.class.getDeclaredField("TAIEX_PATH");
        path.setAccessible(true);

        assertEquals("/indicesReport/MI_5MINS_HIST", path.get(null));
    }
}
