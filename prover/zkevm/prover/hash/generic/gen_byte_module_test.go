package generic

import (
	"testing"

	"github.com/consensys/zkevm-monorepo/prover/maths/field"
	"github.com/consensys/zkevm-monorepo/prover/protocol/wizard"
	"github.com/consensys/zkevm-monorepo/prover/utils"
	"github.com/consensys/zkevm-monorepo/prover/zkevm/prover/common"
	"github.com/stretchr/testify/assert"
)

func TestScanByteStream(t *testing.T) {

	var (
		mustDecodeHex = func(s string) []byte {
			b, _ := utils.HexDecodeString(s)
			return b
		}
		gdm     = &GenDataModule{}
		streams = [][]byte{
			mustDecodeHex("0x067845465329789679797Ffed67658"),
			mustDecodeHex("0xab477997edff98089860"),
		}
		recoveredStreams = [][]byte{}
	)

	comp := wizard.Compile(func(build *wizard.Builder) {

		*gdm = GenDataModule{
			HashNum: build.RegisterCommit("A", 32),
			Limb:    build.RegisterCommit("B", 32),
			TO_HASH: build.RegisterCommit("C", 32),
			NBytes:  build.RegisterCommit("D", 32),
		}
	})

	_ = wizard.Prove(comp, func(run *wizard.ProverRuntime) {

		assignGdbFromStream(run, gdm, streams)
		recoveredStreams = gdm.ScanStreams(run)
	})

	for i := range streams {
		assert.Equalf(t,
			utils.HexEncodeToString(streams[i]),
			utils.HexEncodeToString(recoveredStreams[i]),
			"position %v", i,
		)
	}
}

func assignGdbFromStream(run *wizard.ProverRuntime, gdm *GenDataModule, stream [][]byte) {

	var (
		limbs   = common.NewVectorBuilder(gdm.Limb)
		hashNum = common.NewVectorBuilder(gdm.HashNum)
		nbBytes = common.NewVectorBuilder(gdm.NBytes)
		toHash  = common.NewVectorBuilder(gdm.TO_HASH)
	)

	for hashID := range stream {

		currStream := stream[hashID]
		for i := 0; i < len(currStream); i += 2 {

			var (
				currNbBytes = utils.Min(len(currStream)-i, 2)
				currLimb    = currStream[i : i+currNbBytes]
				currLimbLA  = [32]byte{}
			)

			copy(currLimbLA[16:], currLimb[:currNbBytes])
			limbs.PushLo(currLimbLA)
			hashNum.PushInt(hashID + 1)
			nbBytes.PushInt(currNbBytes)
			toHash.PushOne()
		}

	}

	limbs.PadAndAssign(run, field.Zero())
	hashNum.PadAndAssign(run, field.Zero())
	nbBytes.PadAndAssign(run, field.Zero())
	toHash.PadAndAssign(run, field.Zero())
}