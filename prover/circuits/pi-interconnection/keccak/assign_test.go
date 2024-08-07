package keccak

import (
	"github.com/consensys/gnark-crypto/ecc"
	"github.com/consensys/gnark/frontend"
	"github.com/consensys/gnark/test"
	"github.com/consensys/zkevm-monorepo/prover/circuits/internal"
	"github.com/consensys/zkevm-monorepo/prover/protocol/compiler/dummy"
	"github.com/stretchr/testify/assert"
	"testing"
)

func TestAssign(t *testing.T) {

	compiler := NewStrictHasherCompiler(1)
	compiled := compiler.WithHashLengths(32).Compile(10, dummy.Compile)

	var zero [32]byte

	hsh := compiled.GetHasher()
	_, err := hsh.Write(zero[:])
	assert.NoError(t, err)
	res := hsh.Sum(nil)

	circuit := testAssignCircuit{
		Ins:  [][][32]frontend.Variable{make([][32]frontend.Variable, 1)},
		Outs: make([][32]frontend.Variable, 1),
	}
	circuit.H, err = compiled.GetCircuit()
	assert.NoError(t, err)

	assignment := testAssignCircuit{
		H:    StrictHasherCircuit{},
		Ins:  [][][32]frontend.Variable{make([][32]frontend.Variable, 1)},
		Outs: make([][32]frontend.Variable, 1),
	}
	assignment.H, err = hsh.Assign()
	assert.NoError(t, err)
	internal.Copy(assignment.Outs[0][:], res)
	internal.Copy(assignment.Ins[0][0][:], zero[:])

	assert.NoError(t, test.IsSolved(&circuit, &assignment, ecc.BLS12_377.ScalarField()))
}

func (c *testAssignCircuit) Define(api frontend.API) error {
	hsh := c.H.NewHasher(api)
	for i := range c.Ins {
		out := hsh.Sum(nil, c.Ins[i]...)
		internal.AssertSliceEquals(api, c.Outs[i][:], out[:])
	}
	return hsh.Finalize()
}

type testAssignCircuit struct {
	H    StrictHasherCircuit
	Ins  [][][32]frontend.Variable
	Outs [][32]frontend.Variable
}