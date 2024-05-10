package net.consensys.zkevm.persistence.dao.batch.persistence

import net.consensys.zkevm.domain.Batch
import tech.pegasys.teku.infrastructure.async.SafeFuture

class DuplicatedBatchException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

interface BatchesRepository {
  fun saveNewBatch(batch: Batch): SafeFuture<Unit>

  fun findHighestConsecutiveEndBlockNumberFromBlockNumber(
    startingBlockNumberInclusive: Long
  ): SafeFuture<Long?>

  fun setBatchStatusUpToEndBlockNumber(
    endBlockNumberInclusive: Long,
    currentStatus: Batch.Status,
    newStatus: Batch.Status
  ): SafeFuture<Int>

  fun deleteBatchesUpToEndBlockNumber(
    endBlockNumberInclusive: Long
  ): SafeFuture<Int>

  fun deleteBatchesAfterBlockNumber(
    startingBlockNumberInclusive: Long
  ): SafeFuture<Int>
}
