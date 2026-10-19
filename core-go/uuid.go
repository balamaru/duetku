package core

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
)

// NewUUID creates a random RFC 4122 version 4 identifier without a dependency.
func NewUUID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16]), nil
}
func ValidUUID(id string) bool {
	if len(id) != 36 || id[8] != '-' || id[13] != '-' || id[18] != '-' || id[23] != '-' {
		return false
	}
	raw := id[:8] + id[9:13] + id[14:18] + id[19:23] + id[24:]
	_, err := hex.DecodeString(raw)
	return err == nil && id[14] == '4' && (id[19] == '8' || id[19] == '9' || id[19] == 'a' || id[19] == 'b')
}
