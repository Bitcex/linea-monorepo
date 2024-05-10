package functionals_test

import (
	"testing"

	"github.com/consensys/zkevm-monorepo/prover/maths/common/smartvectors"
	"github.com/consensys/zkevm-monorepo/prover/maths/field"
	"github.com/consensys/zkevm-monorepo/prover/protocol/accessors"
	"github.com/consensys/zkevm-monorepo/prover/protocol/coin"
	"github.com/consensys/zkevm-monorepo/prover/protocol/compiler/dummy"
	"github.com/consensys/zkevm-monorepo/prover/protocol/compiler/splitter"
	"github.com/consensys/zkevm-monorepo/prover/protocol/dedicated/functionals"
	"github.com/consensys/zkevm-monorepo/prover/protocol/ifaces"
	"github.com/consensys/zkevm-monorepo/prover/protocol/wizard"
	"github.com/stretchr/testify/require"
)

func TestEvalBivariateSimple(t *testing.T) {

	wp := smartvectors.ForTest(1, 2, 3, 4, 5, 6, 7, 8)

	x := accessors.NewConstant(field.NewElement(2))
	y := accessors.NewConstant(field.NewElement(3))

	var (
		acc          ifaces.Accessor
		savedRuntime *wizard.ProverRuntime
	)

	definer := func(b *wizard.Builder) {
		p := b.RegisterCommit("P", wp.Len())
		acc = functionals.EvalCoeffBivariate(b.CompiledIOP, "EVAL_BIVARIATE", p, x, y, 4, 2)
	}

	prover := func(run *wizard.ProverRuntime) {
		savedRuntime = run
		run.AssignColumn("P", wp)
	}

	compiled := wizard.Compile(definer,
		dummy.Compile,
	)

	proof := wizard.Prove(compiled, prover)

	accY := acc.GetVal(savedRuntime)
	expectedY := field.NewElement(376)

	require.Equal(t, accY, expectedY)

	err := wizard.Verify(compiled, proof)
	require.NoError(t, err)

}

func TestEvalBivariateWithCoin(t *testing.T) {

	wp := smartvectors.Rand(32)

	definer := func(b *wizard.Builder) {
		p := b.RegisterCommit("P", wp.Len())
		x := accessors.NewFromCoin(b.RegisterRandomCoin("X", coin.Field))
		y := accessors.NewFromCoin(b.RegisterRandomCoin("Y", coin.Field))
		_ = functionals.EvalCoeffBivariate(b.CompiledIOP, "EVAL_BIVARIATE", p, x, y, 4, 8)
	}

	prover := func(run *wizard.ProverRuntime) {
		run.AssignColumn("P", wp)
	}

	compiled := wizard.Compile(definer, dummy.Compile)
	proof := wizard.Prove(compiled, prover)
	err := wizard.Verify(compiled, proof)
	require.NoError(t, err)

}

func TestEvalBivariateWithCoinAndConstant(t *testing.T) {

	/*
		The test consists  in folding a vector of the form
			0, 1, 2, 3, ... n-1
		using the variable 2
	*/
	wpVec := make([]field.Element, 32)
	for i := range wpVec {
		wpVec[i] = field.NewElement(uint64(i))
	}
	wp := smartvectors.NewRegular(wpVec)

	definer := func(b *wizard.Builder) {
		p := b.RegisterCommit("P", wp.Len())
		x := accessors.NewConstant(field.NewElement(2))
		y := accessors.NewConstant(field.NewElement(242))
		_ = functionals.EvalCoeffBivariate(b.CompiledIOP, "EVAL_BIVARIATE", p, x, y, 4, 8)
	}

	prover := func(run *wizard.ProverRuntime) {
		run.AssignColumn("P", wp)
	}

	compiled := wizard.Compile(definer, dummy.Compile)
	proof := wizard.Prove(compiled, prover)
	err := wizard.Verify(compiled, proof)
	require.NoError(t, err)

}

// Test the compatibility of the fold with the splitter
func TestEvalBivariateSimpleWithSplitting(t *testing.T) {

	wp := smartvectors.ForTest(1, 2, 3, 4, 5, 6, 7, 8)

	x := accessors.NewConstant(field.NewElement(2))
	y := accessors.NewConstant(field.NewElement(2))

	definer := func(b *wizard.Builder) {
		p := b.RegisterCommit("P", wp.Len())
		_ = functionals.EvalCoeffBivariate(b.CompiledIOP, "EVAL_BIVARIATE", p, x, y, 4, 2)
	}

	prover := func(run *wizard.ProverRuntime) {
		run.AssignColumn("P", wp)
	}

	compiled := wizard.Compile(definer,
		splitter.SplitColumns(4),
		dummy.Compile,
	)

	proof := wizard.Prove(compiled, prover)
	err := wizard.Verify(compiled, proof)
	require.NoError(t, err)
}
