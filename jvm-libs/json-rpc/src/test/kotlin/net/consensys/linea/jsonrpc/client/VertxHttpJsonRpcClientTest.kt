package net.consensys.linea.jsonrpc.client

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.ok
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.status
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.EqualToJsonPattern
import com.github.tomakehurst.wiremock.matching.EqualToPattern
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpVersion
import io.vertx.core.json.JsonObject
import net.consensys.linea.async.get
import net.consensys.linea.async.toSafeFuture
import net.consensys.linea.jsonrpc.JsonRpcError
import net.consensys.linea.jsonrpc.JsonRpcErrorResponse
import net.consensys.linea.jsonrpc.JsonRpcRequestListParams
import net.consensys.linea.jsonrpc.JsonRpcSuccessResponse
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.spy
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import java.net.URL
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class VertxHttpJsonRpcClientTest {
  private lateinit var vertx: Vertx
  private lateinit var client: VertxHttpJsonRpcClient
  private lateinit var wiremock: WireMockServer
  private val path = "/api/v1?appKey=1234"
  private lateinit var meterRegistry: SimpleMeterRegistry
  private val clientOptions =
    HttpClientOptions()
      .setKeepAlive(true)
      .setProtocolVersion(HttpVersion.HTTP_1_1)
      .setMaxPoolSize(10)
  private lateinit var endpoint: URL

  @BeforeEach
  fun setUp() {
    vertx = Vertx.vertx()
    wiremock = WireMockServer(WireMockConfiguration.options().dynamicPort())
    wiremock.start()
    endpoint = URL(wiremock.baseUrl() + path)
    meterRegistry = SimpleMeterRegistry()
    client = VertxHttpJsonRpcClient(vertx.createHttpClient(clientOptions), endpoint, meterRegistry)
  }

  @AfterEach
  fun tearDown() {
    wiremock.stop()
    vertx.close()
  }

  @Test
  fun makesRequest_makesCorrectJsonRpcRequest() {
    replyRequestWith(JsonObject().put("jsonrpc", "2.0").put("id", "1").put("result", null))
    val params =
      listOf(
        "superUser",
        JsonObject()
          .put("name", "Alice")
          .put("email", "alice@wonderland.io")
          .put("address", Bytes.fromHexString("0xaabbccdd").toArray())
      )
    client.makeRequest(JsonRpcRequestListParams("2.0", 1, "addUser", params)).get()

    val expectedJsonBody =
      """{
        |"jsonrpc": "2.0",
        |"id": 1,
        |"method": "addUser",
        |"params": [
        | "superUser", {
        |   "name":"Alice",
        |   "email":"alice@wonderland.io",
        |   "address":"0xaabbccdd"
        |   }
        | ]
        |}"""
        .trimMargin()

    wiremock.verify(
      RequestPatternBuilder.newRequestPattern()
        .withPort(wiremock.port())
        .withUrl(path)
        .withHeader("content-type", EqualToPattern("application/json"))
        .withRequestBody(
          EqualToJsonPattern(
            expectedJsonBody, /*ignoreArrayOrder*/
            false, /*ignoreExtraElements*/
            false
          )
        )
    )
  }

  @Test
  fun makesRequest_successNullResult() {
    replyRequestWith(JsonObject().put("jsonrpc", "2.0").put("id", "1").put("result", null))

    val response =
      client.makeRequest(JsonRpcRequestListParams("2.0", 1, "eth_blockNumber", emptyList())).get()

    assertThat(response).isEqualTo(Ok(JsonRpcSuccessResponse("1", null)))
  }

  @Test
  fun makesRequest_successSingleValue() {
    replyRequestWith(JsonObject().put("jsonrpc", "2.0").put("id", "1").put("result", 3))

    val response =
      client.makeRequest(JsonRpcRequestListParams("2.0", 1, "randomNumber", emptyList())).get()

    assertThat(response).isEqualTo(Ok(JsonRpcSuccessResponse("1", 3)))
  }

  @Test
  fun makesRequest_successJsonObject() {
    replyRequestWith(
      JsonObject()
        .put("jsonrpc", "2.0")
        .put("id", "1")
        .put("result", JsonObject().put("odd", 23).put("even", 10))
    )

    val response =
      client.makeRequest(JsonRpcRequestListParams("2.0", 1, "randomNumbers", emptyList())).get()

    assertThat(response)
      .isEqualTo(Ok(JsonRpcSuccessResponse("1", JsonObject().put("odd", 23).put("even", 10))))
  }

  @Test
  fun makesRequest_successWithMapper() {
    replyRequestWith(
      JsonObject().put("jsonrpc", "2.0").put("id", "1").put("result", "some_random_value")
    )
    val resultMapper = { value: Any? -> (value as String).uppercase() }
    val response =
      client
        .makeRequest(JsonRpcRequestListParams("2.0", 1, "randomNumbers", emptyList()), resultMapper)
        .get()

    assertThat(response).isEqualTo(Ok(JsonRpcSuccessResponse("1", "SOME_RANDOM_VALUE")))
  }

  @Test
  fun makesRequest_Error() {
    replyRequestWith(
      JsonObject()
        .put("jsonrpc", "2.0")
        .put("id", "1")
        .put(
          "error",
          JsonObject()
            .put("code", -32602)
            .put("message", "Invalid params")
            .put("data", JsonObject().put("k", "v"))
        )
    )
    val response =
      client.makeRequest(JsonRpcRequestListParams("2.0", 1, "randomNumbers", emptyList())).get()

    assertThat(response)
      .isEqualTo(
        Err(
          JsonRpcErrorResponse(
            "1",
            JsonRpcError(-32602, "Invalid params", mapOf("k" to "v"))
          )
        )
      )
  }

  @Test
  fun makesRequest_ParseErrorNoId() {
    replyRequestWith(
      JsonObject()
        .put("jsonrpc", "2.0")
        .put("error", JsonObject().put("code", -32602).put("message", "Parse Error"))
    )
    val response =
      client.makeRequest(JsonRpcRequestListParams("2.0", 1, "randomNumbers", emptyList())).get()

    assertThat(response)
      .isEqualTo(Err(JsonRpcErrorResponse(null, JsonRpcError(-32602, "Parse Error"))))
  }

  @Test
  @Timeout(15, unit = TimeUnit.SECONDS)
  fun makesRequest_malFormattedJsonResponse() {
    replyRequestWith(
      JsonObject().put("jsonrpc", "2.0").put("id", "1").put("nonsense", "some_random_value")
    )

    assertThat(
      client
        .makeRequest(JsonRpcRequestListParams("2.0", 1, "randomNumbers", emptyList()))
        .toSafeFuture()
    )
      .failsWithin(Duration.ofSeconds(14))
      .withThrowableOfType(ExecutionException::class.java)
      .withMessage(
        "java.lang.IllegalArgumentException: Invalid JSON-RPC response without result or error"
      )
  }

  @Test
  @Timeout(15, unit = TimeUnit.SECONDS)
  fun makesRequest_connectionFailure() {
    val log: Logger = spy(LogManager.getLogger(VertxHttpJsonRpcClient::class.java))
    val endpoint = URL("http://service-not-available:1234/api/v1?appKey=1234")
    client = VertxHttpJsonRpcClient(
      vertx.createHttpClient(clientOptions),
      endpoint,
      meterRegistry,
      log = log
    )

    val request = JsonRpcRequestListParams("2.0", 1, "randomNumbers", emptyList())
    assertThat(client.makeRequest(request).toSafeFuture())
      .failsWithin(Duration.ofSeconds(14))
      .withThrowableOfType(ExecutionException::class.java)
      .withMessageContaining(
        "java.net.UnknownHostException: Failed to resolve 'service-not-available'"
      )

    verify(log).log(
      eq(Level.DEBUG),
      eq("<--> {} {} failed with error={}"),
      eq(endpoint),
      eq(JsonObject.mapFrom(request).encode()),
      any<String>(),
      any<Throwable>()
    )
  }

  @Test
  @Timeout(15, unit = TimeUnit.SECONDS)
  fun makesRequest_502_response() {
    replyRequestWith(500, "Internal server error\n 2nd line of response to be ignored")
    val log: Logger = spy(LogManager.getLogger(VertxHttpJsonRpcClient::class.java))
    client = VertxHttpJsonRpcClient(
      vertx.createHttpClient(clientOptions),
      endpoint,
      meterRegistry,
      log = log
    )

    val request = JsonRpcRequestListParams("2.0", 1, "randomNumbers", emptyList())
    assertThat(client.makeRequest(request).toSafeFuture())
      .failsWithin(Duration.ofSeconds(14))
      .withThrowableOfType(ExecutionException::class.java)
      .withMessageContaining("HTTP errorCode=500, message=Server Error")

    verify(log).log(
      eq(Level.DEBUG),
      eq("<--> {} {} failed with error={}"),
      eq(endpoint),
      eq(JsonObject.mapFrom(request).encode()),
      any<String>(),
      any<Throwable>()
    )
  }

  @Test
  fun makesRequest_measuresRequest() {
    replyRequestWith(JsonObject().put("jsonrpc", "2.0").put("id", "1").put("result", 3))

    client.makeRequest(JsonRpcRequestListParams("2.0", 1, "randomNumber", emptyList())).get()
    client.makeRequest(JsonRpcRequestListParams("2.0", 1, "randomNumber", emptyList())).get()
    client.makeRequest(JsonRpcRequestListParams("2.0", 1, "randomNumber", emptyList())).get()

    val timer =
      meterRegistry.timer(
        "jsonrpc.request",
        listOf(Tag.of("method", "randomNumber"), Tag.of("endpoint", "localhost"))
      )

    assertThat(timer).isNotNull
    assertThat(timer.count()).isEqualTo(3)
  }

  private fun replyRequestWith(jsonRpcResponse: JsonObject) {
    wiremock.stubFor(
      post(path)
        .withHeader("Content-Type", containing("application/json"))
        .willReturn(
          ok()
            .withHeader("Content-type", "application/json")
            .withBody(jsonRpcResponse.toString())
        )
    )
  }

  private fun replyRequestWith(statusCode: Int, body: String?) {
    wiremock.stubFor(
      post(path)
        .withHeader("Content-Type", containing("application/json"))
        .willReturn(
          status(statusCode)
            .withHeader("Content-type", "text/plain")
            .apply { if (body != null) withBody(body) }
        )
    )
  }
}
