/**
 * Internal DTO records shared across engines / services in the
 * production-swing-redesign pipeline (Regime → Theme → Ranking → Setup →
 * Timing → Risk → Execution → Review).
 *
 * <p>These are <b>not REST request/response DTOs</b>. They carry intermediate
 * decision artifacts between layers and are not intended to appear on the HTTP
 * boundary unchanged. REST responses should wrap or flatten them explicitly.</p>
 */
package com.austin.trading.dto.internal;
