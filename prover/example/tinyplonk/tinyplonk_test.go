package tinyplonk

import (
	"testing"

	"github.com/consensys/gnark/frontend"
	"github.com/consensys/linea-monorepo/prover/maths/field"
	"github.com/consensys/linea-monorepo/prover/protocol/compiler/cleanup"
	"github.com/consensys/linea-monorepo/prover/protocol/compiler/globalcs"
	"github.com/consensys/linea-monorepo/prover/protocol/compiler/innerproduct"
	"github.com/consensys/linea-monorepo/prover/protocol/compiler/localcs"
	"github.com/consensys/linea-monorepo/prover/protocol/compiler/lookup"
	"github.com/consensys/linea-monorepo/prover/protocol/compiler/permutation"
	"github.com/consensys/linea-monorepo/prover/protocol/compiler/specialqueries"
	"github.com/consensys/linea-monorepo/prover/protocol/compiler/univariates"
	"github.com/consensys/linea-monorepo/prover/protocol/compiler/vortex"
	"github.com/consensys/linea-monorepo/prover/protocol/wizard"
)

// FibonacciCircuit is a circuit enforcing that U50 is the 50-th term of a
// sequence defined by the recursion U[i+2] = U[i+1] + U[i] given U[0] and U[1].
type FibonacciCircuit struct {
	// U0, U1 are the initial values of a Fibonacci sequence
	U0, U1 frontend.Variable `gnark:",public"`
	// U50 is the 50-th term of the sequence
	U50 frontend.Variable `gnark:",public"`
}

// Define implements the [frontend.Circuit] interface
func (f *FibonacciCircuit) Define(api frontend.API) error {

	var (
		prevprev = f.U0
		prev     = f.U1
	)

	for i := 2; i <= 50; i++ {
		prevprev, prev = prev, api.Add(prevprev, prev)
	}

	api.AssertIsEqual(prev, f.U50)

	return nil
}

func TestTinyPlonk(t *testing.T) {

	var (
		plk *TinyPlonkCS
	)

	define := func(bui *wizard.Builder) {
		plk = DefineFromGnark(bui.CompiledIOP, &FibonacciCircuit{})
	}

	comp := wizard.Compile(
		// our "Define" define function
		define,
		// A list of compiler steps to construct the proof system
		specialqueries.RangeProof,
		specialqueries.CompileFixedPermutations,
		permutation.CompileGrandProduct,
		lookup.CompileLogDerivative,
		innerproduct.Compile,
		cleanup.CleanUp,
		localcs.Compile,
		globalcs.Compile,
		univariates.CompileLocalOpening,
		univariates.Naturalize,
		univariates.MultiPointToSinglePoint(64),
		vortex.Compile(2),
	)

	prove := func(run *wizard.ProverRuntime) {
		plk.AssignFromGnark(run, &FibonacciCircuit{
			U0:  0,
			U1:  1,
			U50: fibo(field.Zero(), field.One(), 50),
		})
	}

	var (
		proof = wizard.Prove(comp, prove)
		err   = wizard.Verify(comp, proof)
	)

	if err != nil {
		t.Fatalf("the verification failed: %v", err)
	}
}

// fibo returns the n-th term of a Fibonacci sequence generated by u0 and u1
func fibo(u0, u1 field.Element, n int) field.Element {

	var (
		prevprev = u0
		prev     = u1
	)

	for i := 2; i <= n; i++ {
		new := new(field.Element).Add(&prevprev, &prev)
		prevprev, prev = prev, *new
	}

	return prev
}