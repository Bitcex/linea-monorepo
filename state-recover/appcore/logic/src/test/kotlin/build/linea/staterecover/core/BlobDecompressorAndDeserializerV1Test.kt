package build.linea.staterecover.core

import build.linea.staterecover.BlockL1RecoveredData
import build.linea.staterecover.TransactionL1RecoveredData
import kotlinx.datetime.Instant
import net.consensys.encodeHex
import net.consensys.linea.blob.BlobCompressorVersion
import net.consensys.linea.blob.BlobDecompressorVersion
import net.consensys.linea.blob.GoNativeBlobCompressor
import net.consensys.linea.blob.GoNativeBlobCompressorFactory
import net.consensys.linea.blob.GoNativeBlobDecompressorFactory
import net.consensys.linea.nativecompressor.CompressorTestData
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.hyperledger.besu.datatypes.Address
import org.hyperledger.besu.ethereum.core.Block
import org.hyperledger.besu.ethereum.core.Transaction
import org.hyperledger.besu.ethereum.core.encoding.registry.BlockDecoder
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPInput
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.security.Security
import kotlin.jvm.optionals.getOrNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlobDecompressorAndDeserializerV1Test {
  private lateinit var compressor: GoNativeBlobCompressor
  private val blockStaticFields = BlockHeaderStaticFields(
    coinbase = Address.ZERO.toArray(),
    gasLimit = 30_000_000UL,
    difficulty = 0UL
  )

  private lateinit var decompressorToDomain: BlobDecompressorAndDeserializer

  companion object {
    init {
      Security.addProvider(BouncyCastleProvider())
    }
  }

  @BeforeEach
  fun setUp() {
    compressor = GoNativeBlobCompressorFactory
      .getInstance(BlobCompressorVersion.V1_0_1)
      .apply {
        Init(124 * 1024, GoNativeBlobCompressorFactory.dictionaryPath.toAbsolutePath().toString())
        Reset()
      }
    val decompressor = GoNativeBlobDecompressorFactory.getInstance(BlobDecompressorVersion.V1_1_0)
    decompressorToDomain = BlobDecompressorToDomainV1(decompressor, blockStaticFields)
  }

  @Test
  fun `should decompress block and transactions`() {
    val blocksRLP = CompressorTestData.blocksRlpEncoded.toList()
    val blocks = run {
      val decoder = BlockDecoder.builder().build()
      val mainnetFunctions = MainnetBlockHeaderFunctions()
      blocksRLP.map { blockRlp ->
        decoder.decode(
          BytesValueRLPInput(Bytes.wrap(blockRlp), false),
          mainnetFunctions
        )
      }
    }
    val startingBlockNumber = blocks[0].header.number.toULong()

    val blob1 = compress(blocksRLP.slice(0..2))
    val blob2 = compress(blocksRLP.slice(3..3))

    val recoveredBlocks = decompressorToDomain.decompress(
      startBlockNumber = startingBlockNumber,
      blobs = listOf(blob1, blob2)
    )
    assertThat(recoveredBlocks[0].blockNumber).isEqualTo(startingBlockNumber)

    recoveredBlocks.zip(blocks) { recoveredBlock, originalBlock ->
      assertBlockData(recoveredBlock, originalBlock)
    }
  }

  private fun assertBlockData(
    uncompressed: BlockL1RecoveredData,
    original: Block
  ) {
    println("asserting block: ${original.header.number}")
    assertThat(uncompressed.blockNumber).isEqualTo(original.header.number.toULong())
    assertThat(uncompressed.blockHash).isEqualTo(original.header.hash.toArray())
    assertThat(uncompressed.coinbase).isEqualTo(blockStaticFields.coinbase)
    assertThat(uncompressed.blockTimestamp).isEqualTo(Instant.fromEpochSeconds(original.header.timestamp))
    assertThat(uncompressed.gasLimit).isEqualTo(blockStaticFields.gasLimit)
    assertThat(uncompressed.difficulty).isEqualTo(0UL)
    uncompressed.transactions.zip(original.body.transactions) { a, b ->
      assertTransactionData(a, b)
    }
  }

  private fun assertTransactionData(
    uncompressed: TransactionL1RecoveredData,
    original: Transaction
  ) {
    assertThat(uncompressed.type).isEqualTo(original.type.serializedType.toUByte())
    assertThat(uncompressed.from).isEqualTo(original.sender.toArray())
    assertThat(uncompressed.nonce).isEqualTo(original.nonce.toULong())
    assertThat(uncompressed.to).isEqualTo(original.to.getOrNull()?.toArray())
    assertThat(uncompressed.gasLimit).isEqualTo(original.gasLimit.toULong())
    assertThat(uncompressed.maxFeePerGas).isEqualTo(original.maxFeePerGas.getOrNull()?.asBigInteger)
    assertThat(uncompressed.maxPriorityFeePerGas).isEqualTo(original.maxPriorityFeePerGas.getOrNull()?.asBigInteger)
    assertThat(uncompressed.gasPrice).isEqualTo(original.gasPrice.getOrNull()?.asBigInteger)
    assertThat(uncompressed.value).isEqualTo(original.value.asBigInteger)
    assertThat(uncompressed.data?.encodeHex()).isEqualTo(original.data.getOrNull()?.toArray()?.encodeHex())
    if (uncompressed.accessList.isNullOrEmpty() != original.accessList.getOrNull().isNullOrEmpty()) {
      assertThat(uncompressed.accessList).isEqualTo(original.accessList.getOrNull())
    } else {
      uncompressed.accessList?.zip(original.accessList.getOrNull()!!) { a, b ->
        assertThat(a.address).isEqualTo(b.address.toArray())
        assertThat(a.storageKeys).isEqualTo(b.storageKeys.map { it.toArray() })
      }
    }
  }

  private fun compressBlocks(blocks: List<Block>): ByteArray {
    return compress(blocks.map { block -> block.toRlp().toArray() })
  }

  private fun compress(blocks: List<ByteArray>): ByteArray {
    blocks.forEach { blockRlp ->
      compressor.Write(blockRlp, blockRlp.size)
      if (compressor.Error()?.isNotEmpty() == true) {
        throw RuntimeException("Failed to compress block, error='${compressor.Error()}'")
      }
    }

    val compressedData = ByteArray(compressor.Len())
    compressor.Bytes(compressedData)
    compressor.Reset()
    return compressedData
  }
}
