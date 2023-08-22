package files_test

import (
	"crypto/rand"
	"path"
	"testing"

	"github.com/consensys/accelerated-crypto-monorepo/backend/files"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	DIR string = "/tmp/go-file-test"
)

func TestFilesCompressed(t *testing.T) {

	// Write random bytes
	fileSize := 1 << 12
	b := make([]byte, fileSize)
	_, err := rand.Reader.Read(b)
	require.NoError(t, err)

	path := path.Join(DIR, "rand")

	w := files.MustWriteCompressed(path)
	_, err = w.Write(b)
	require.NoError(t, err)

	w.Close()

	r := files.MustReadCompressed(path)
	newB := make([]byte, fileSize)
	_, err = r.Read(newB)
	require.NoError(t, err)

	assert.Equal(t, b, newB)

}

func TestFilesRW(t *testing.T) {

	// Write random bytes
	fileSize := 1 << 12
	b := make([]byte, fileSize)
	_, err := rand.Reader.Read(b)
	require.NoError(t, err)

	path := path.Join(DIR, "rand")

	w := files.MustOverwrite(path)
	_, err = w.Write(b)
	require.NoError(t, err)

	w.Close()

	r := files.MustRead(path)
	newB := make([]byte, fileSize)
	_, err = r.Read(newB)
	require.NoError(t, err)

	assert.Equal(t, b, newB)
}
