package v0

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"github.com/consensys/zkevm-monorepo/prover/backend/ethereum"
	"github.com/consensys/zkevm-monorepo/prover/lib/compressor/blob/encode"
	"github.com/ethereum/go-ethereum/common"
	"io"

	"github.com/ethereum/go-ethereum/core/types"
	"github.com/ethereum/go-ethereum/rlp"
)

// EncodeBlockForCompression encodes a block for compression.
func EncodeBlockForCompression(block *types.Block, w io.Writer) error {
	if err := binary.Write(w, binary.LittleEndian, block.Time()); err != nil {
		return err
	}
	for _, tx := range block.Transactions() {
		if err := EncodeTxForCompression(tx, w); err != nil {
			return err
		}
	}
	return nil
}

// EncodeTxForCompression encodes a transaction for compression.
// this code is from zk-evm-monorepo/prover/... but doesn't include the chainID
func EncodeTxForCompression(tx *types.Transaction, w io.Writer) error {
	switch {
	// LONDON with dynamic fees
	case tx.Type() == types.DynamicFeeTxType:
		if _, err := w.Write([]byte{tx.Type()}); err != nil {
			return err
		}
		if err := rlp.Encode(w, []interface{}{
			// tx.ChainID(),
			tx.Nonce(),
			tx.GasTipCap(),
			tx.GasFeeCap(),
			tx.Gas(),
			ethereum.GetFrom(tx),
			tx.To(),
			tx.Value(),
			tx.Data(),
			tx.AccessList(),
		}); err != nil {
			return err
		}
	// EIP2390 transaction with access-list
	case tx.Type() == types.AccessListTxType:
		if _, err := w.Write([]byte{tx.Type()}); err != nil {
			return err
		}
		if err := rlp.Encode(w, []interface{}{
			// tx.ChainID(),
			tx.Nonce(),
			tx.GasPrice(),
			tx.Gas(),
			ethereum.GetFrom(tx),
			tx.To(),
			tx.Value(),
			tx.Data(),
			tx.AccessList(),
		}); err != nil {
			return err
		}
	// EIP155 signature with protection against replay
	case tx.Type() == types.LegacyTxType && tx.Protected():
		if err := rlp.Encode(w, []interface{}{
			tx.Nonce(),
			tx.GasPrice(),
			tx.Gas(),
			ethereum.GetFrom(tx),
			tx.To(),
			tx.Value(),
			tx.Data(),
			// tx.ChainID(), uint(0), uint(0),
		}); err != nil {
			return err
		}
	// Homestead signature
	case tx.Type() == types.LegacyTxType && !tx.Protected():
		if err := rlp.Encode(w, []interface{}{
			tx.Nonce(),
			tx.GasPrice(),
			tx.Gas(),
			ethereum.GetFrom(tx),
			tx.To(),
			tx.Value(),
			tx.Data(),
		}); err != nil {
			return err
		}
	default:
		panic(fmt.Sprintf("Unknown type of transaction %v, %++v", tx.Type(), tx))
	}

	return nil
}

// DecodeBlockFromUncompressed inverts [EncodeBlockForCompression]. It is primarily meant for
// testing and ensuring the encoding is bijective.
func DecodeBlockFromUncompressed(r *bytes.Reader) (encode.DecodedBlockData, error) {

	/*
		if err := binary.Write(w, binary.LittleEndian, block.Time()); err != nil {
			return err
		}
		for _, tx := range block.Transactions() {
			if err := EncodeTxForCompression(tx, w); err != nil {
				return err
			}
		}
		return nil
	*/

	var decTimestamp uint64

	if err := binary.Read(r, binary.BigEndian, &decTimestamp); err != nil {
		return encode.DecodedBlockData{}, fmt.Errorf("could not decode timestamp: %w", err)
	}

	decodedBlk := encode.DecodedBlockData{
		Timestamp: decTimestamp,
	}

	for r.Len() != 0 {
		var (
			tx   types.Transaction
			from common.Address
		)
		if err := DecodeTxFromUncompressed(r, &tx, &from); err != nil {
			return encode.DecodedBlockData{}, fmt.Errorf("could not decode transaction #%v: %w", len(decodedBlk.Txs), err)
		}
		decodedBlk.Froms = append(decodedBlk.Froms, from)
		decodedBlk.Txs = append(decodedBlk.Txs, tx)
	}

	return decodedBlk, nil
}

func DecodeTxFromUncompressed(r *bytes.Reader, tx *types.Transaction, from *common.Address) error {
	if _, err := r.Read(from[:]); err != nil {
		return fmt.Errorf("could not read from address: %w", err)
	}

	if err := ethereum.DecodeTxFromBytes(r, tx); err != nil {
		return fmt.Errorf("could not deserialize transaction")
	}

	firstByte, err := r.ReadByte()
	if err != nil {
		return fmt.Errorf("could not read the first byte: %w", err)
	}

	switch {
	case firstByte == types.DynamicFeeTxType:
		return decodeDynamicFeeTx(r, tx, from)
	case firstByte == types.AccessListTxType:
		return decodeAccessListTx(r, tx, from)
	// According to the RLP rule, `0xc0 + x` or `0xf7` indicates that the current
	// item is a list and this is what's used to identify that the transaction is
	// a legacy transaction or a EIP-155 transaction.
	//
	// Note that 0xc0 would indicate an empty list and thus be an invalid tx.
	case firstByte > 0xc0:
		// Set the byte-reader backward so that we can apply the rlp-decoder
		// over it.
		r.UnreadByte()
		return decodeLegacyTx(r, tx, from)
	}

	return fmt.Errorf("unexpected first byte: %x", firstByte)

	return nil
}

func decodeLegacyTx(r *bytes.Reader, tx *types.Transaction, from *common.Address) (err error) {
	decTx := []any{}

	if err := rlp.Decode(r, &decTx); err != nil {
		return fmt.Errorf("could not rlp decode transaction: %w", err)
	}

	if len(decTx) != 7 {
		return fmt.Errorf("unexpected number of field")
	}

	parsedTx := types.LegacyTx{}
	err = errors.Join(
		ethereum.TryCast(&parsedTx.Nonce, decTx[0], "nonce"),
		ethereum.TryCast(&parsedTx.GasPrice, decTx[1], "gas-price"),
		ethereum.TryCast(&parsedTx.Gas, decTx[2], "gas"),
		ethereum.TryCast(from, decTx[3], "from"),
		ethereum.TryCast(&parsedTx.To, decTx[4], "to"),
		ethereum.TryCast(&parsedTx.Value, decTx[5], "value"),
		ethereum.TryCast(&parsedTx.Data, decTx[6], "data"),
	)

	*tx = *types.NewTx(&parsedTx)
	return err
}

func decodeAccessListTx(r *bytes.Reader, tx *types.Transaction, from *common.Address) (err error) {

	decTx := []any{}

	if err := rlp.Decode(r, &decTx); err != nil {
		return fmt.Errorf("could not rlp decode transaction: %w", err)
	}

	if len(decTx) != 8 {
		return fmt.Errorf("invalid number of field for a dynamic transaction")
	}

	parsedTx := types.AccessListTx{}
	err = errors.Join(
		ethereum.TryCast(&parsedTx.Nonce, decTx[0], "nonce"),
		ethereum.TryCast(&parsedTx.GasPrice, decTx[1], "gas-price"),
		ethereum.TryCast(&parsedTx.Gas, decTx[2], "gas"),
		ethereum.TryCast(from, decTx[3], "from"),
		ethereum.TryCast(&parsedTx.To, decTx[4], "to"),
		ethereum.TryCast(&parsedTx.Value, decTx[5], "value"),
		ethereum.TryCast(&parsedTx.Data, decTx[6], "data"),
		ethereum.TryCast(&parsedTx.AccessList, decTx[7], "access-list"),
	)

	*tx = *types.NewTx(&parsedTx)
	return err
}

func decodeDynamicFeeTx(r *bytes.Reader, tx *types.Transaction, from *common.Address) (err error) {

	decTx := []any{}

	if err := rlp.Decode(r, &decTx); err != nil {
		return fmt.Errorf("could not rlp decode transaction: %w", err)
	}

	if len(decTx) != 9 {
		return fmt.Errorf("invalid number of field for a dynamic transaction")
	}

	parsedTx := types.DynamicFeeTx{}
	err = errors.Join(
		ethereum.TryCast(&parsedTx.Nonce, decTx[0], "nonce"),
		ethereum.TryCast(&parsedTx.GasTipCap, decTx[1], "gas-tip-cap"),
		ethereum.TryCast(&parsedTx.GasFeeCap, decTx[2], "gas-fee-cap"),
		ethereum.TryCast(&parsedTx.Gas, decTx[3], "gas"),
		ethereum.TryCast(from, decTx[4], "from"),
		ethereum.TryCast(&parsedTx.To, decTx[5], "to"),
		ethereum.TryCast(&parsedTx.Value, decTx[6], "value"),
		ethereum.TryCast(&parsedTx.Data, decTx[7], "data"),
		ethereum.TryCast(&parsedTx.AccessList, decTx[8], "access-list"),
	)
	*tx = *types.NewTx(&parsedTx)
	return err

}
