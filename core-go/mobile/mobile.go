// Package mobile provides a coarse-grained, gomobile-compatible prototype bridge.
package mobile

import (
	"bytes"
	"encoding/json"
	"errors"
	"math"
	"time"

	core "kelolauangku/core"
)

// Store owns an Android SQLite transaction on a dedicated database thread.
// Begin returns one snapshot; Finish applies one mutation batch then commits.
// Abort rolls back. All methods must sanitize storage errors.
type Store interface {
	Begin(write bool) (string, error)
	Finish(changes string) error
	Abort() error
}

// Ping is the same fixed workload used by the native and Go startup spike.
func Ping() string { return "ready" }

type clock struct{ now time.Time }

func (c clock) Now() time.Time { return c.now }

type snapshot struct {
	Categories   []core.Category
	Transactions []core.Transaction
	LastID       int64
}
type change struct {
	TransactionID string `json:",omitempty"`
	Kind          string
	Category      *core.Category    `json:",omitempty"`
	Transaction   *core.Transaction `json:",omitempty"`
	ID            int64             `json:",omitempty"`
}
type repository struct{ store Store }
type transaction struct {
	state   snapshot
	changes []change
}
type request struct {
	Appendix, Notes bool
	TransactionID   string
	ID              int64
	Input           core.Input
	Filter          core.Filter
	Name, Type      string
	Active          bool
	Scope           core.DeleteScope
	Preview         core.DeletePreview
	Confirmed       bool
}
type response struct {
	OK    bool
	Data  any         `json:",omitempty"`
	Error *core.Error `json:",omitempty"`
}

func domainError(code, field, message string) error {
	return &core.Error{Code: code, Field: field, Message: message}
}
func reply(data any, err error) string {
	result := response{OK: err == nil, Data: data}
	if err != nil {
		result.Data = nil
		var typed *core.Error
		if errors.As(err, &typed) {
			result.Error = typed
		} else {
			result.Error = &core.Error{Code: "STORAGE_ERROR", Message: "Penyimpanan lokal gagal. Silakan coba lagi."}
		}
	}
	encoded, e := json.Marshal(result)
	if e != nil {
		return `{"OK":false,"Error":{"Code":"INVALID_DATA","Message":"Data tidak dapat diproses."}}`
	}
	return string(encoded)
}

// Execute is synchronous. Call off the UI thread and serialize calls per store.
// now is RFC3339 with the device's current UTC offset, not an unzoned local date.
func Execute(store Store, operation, payload, now string) string {
	stamp, err := time.Parse(time.RFC3339Nano, now)
	if err != nil {
		return reply(nil, domainError("INVALID_DATE", "now", "Waktu perangkat tidak sah."))
	}
	if store == nil {
		return reply(nil, domainError("INVALID_CONFIGURATION", "store", "Penyimpanan wajib tersedia."))
	}
	var input request
	if payload == "" {
		payload = "{}"
	}
	decoder := json.NewDecoder(bytes.NewBufferString(payload))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&input); err != nil || !json.Valid([]byte(payload)) {
		return reply(nil, domainError("INVALID_REQUEST", "payload", "Permintaan tidak sah."))
	}
	service, err := core.New(&repository{store}, clock{stamp})
	if err != nil {
		return reply(nil, err)
	}
	var result any
	switch operation {
	case "seed":
		err = service.SeedCategories()
	case "window":
		result, err = service.RetentionWindow()
	case "saveTransaction":
		result, err = service.SaveTransaction(input.TransactionID, input.Input)
	case "listTransactions":
		result, err = service.ListTransactions(input.Filter)
	case "summary":
		result, err = service.GetSummary(input.Filter)
	case "report":
		result, err = service.Report(input.Filter, input.Appendix, input.Notes)
	case "breakdown":
		result, err = service.GetBreakdown(input.Filter)
	case "categories":
		result, err = service.ListCategories()
	case "saveCategory":
		result, err = service.SaveCategory(input.ID, input.Name, input.Type, input.Active)
	case "deleteCategory":
		result, err = service.DeleteCategory(input.ID)
	case "previewDelete":
		result, err = service.PreviewDelete(input.Scope)
	case "deleteHistory":
		result, err = service.DeleteHistory(input.Preview, input.Confirmed)
	case "purge":
		result, err = service.PurgeExpiredHistory()
	case "exportCSV":
		var csv bytes.Buffer
		var totals core.Summary
		totals, err = service.ExportCSV(input.Filter, &csv)
		if err == nil {
			result = struct {
				CSV     string
				Summary core.Summary
			}{csv.String(), totals}
		}
	default:
		err = domainError("INVALID_REQUEST", "operation", "Operasi tidak dikenal.")
	}
	return reply(result, err)
}

func (r *repository) run(write bool, fn func(*transaction) error) (err error) {
	data, err := r.store.Begin(write)
	if err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			abortErr := r.store.Abort()
			if err == nil {
				err = abortErr
			}
		}
	}()
	tx := &transaction{changes: []change{}}
	if err = json.Unmarshal([]byte(data), &tx.state); err != nil {
		return domainError("INVALID_DATA", "snapshot", "Data lokal tidak sah.")
	}
	for _, c := range tx.state.Categories {
		if c.ID > tx.state.LastID {
			tx.state.LastID = c.ID
		}
	}
	if err = fn(tx); err != nil {
		return err
	}
	changes, err := json.Marshal(tx.changes)
	if err != nil {
		return err
	}
	if err = r.store.Finish(string(changes)); err != nil {
		return err
	}
	committed = true
	return nil
}
func (r *repository) Read(fn func(core.Reader) error) error {
	return r.run(false, func(tx *transaction) error { return fn(tx) })
}
func (r *repository) Write(fn func(core.Writer) error) error {
	return r.run(true, func(tx *transaction) error { return fn(tx) })
}
func (t *transaction) Categories() ([]core.Category, error) {
	return append([]core.Category{}, t.state.Categories...), nil
}
func (t *transaction) Transactions() ([]core.Transaction, error) {
	return append([]core.Transaction{}, t.state.Transactions...), nil
}
func (t *transaction) Category(id int64) (core.Category, error) {
	for _, c := range t.state.Categories {
		if c.ID == id {
			return c, nil
		}
	}
	return core.Category{}, domainError("NOT_FOUND", "categoryId", "Kategori tidak ditemukan.")
}
func (t *transaction) Transaction(id string) (core.Transaction, error) {
	for _, v := range t.state.Transactions {
		if v.ID == id {
			return v, nil
		}
	}
	return core.Transaction{}, domainError("NOT_FOUND", "id", "Transaksi tidak ditemukan.")
}
func (t *transaction) next() (int64, error) {
	if t.state.LastID == math.MaxInt64 {
		return 0, domainError("INVALID_DATA", "id", "Batas ID tercapai.")
	}
	t.state.LastID++
	return t.state.LastID, nil
}
func (t *transaction) SaveCategory(c core.Category) (core.Category, error) {
	if c.ID == 0 {
		id, e := t.next()
		if e != nil {
			return c, e
		}
		c.ID = id
		t.state.Categories = append(t.state.Categories, c)
	} else {
		found := false
		for i, v := range t.state.Categories {
			if v.ID == c.ID {
				t.state.Categories[i] = c
				found = true
				break
			}
		}
		if !found {
			return c, domainError("NOT_FOUND", "id", "Kategori tidak ditemukan.")
		}
	}
	t.changes = append(t.changes, change{Kind: "saveCategory", Category: &c})
	return c, nil
}
func (t *transaction) SaveTransaction(v core.Transaction) (core.Transaction, error) {
	if v.ID == "" {
		id, e := core.NewUUID()
		if e != nil {
			return v, e
		}
		v.ID = id
		t.state.Transactions = append(t.state.Transactions, v)
	} else {
		found := false
		for i, old := range t.state.Transactions {
			if old.ID == v.ID {
				t.state.Transactions[i] = v
				found = true
				break
			}
		}
		if !found {
			return v, domainError("NOT_FOUND", "id", "Transaksi tidak ditemukan.")
		}
	}
	t.changes = append(t.changes, change{Kind: "saveTransaction", Transaction: &v})
	return v, nil
}
func (t *transaction) DeleteCategory(id int64) error {
	if _, e := t.Category(id); e != nil {
		return e
	}
	for i, v := range t.state.Categories {
		if v.ID == id {
			t.state.Categories = append(t.state.Categories[:i], t.state.Categories[i+1:]...)
			break
		}
	}
	t.changes = append(t.changes, change{Kind: "deleteCategory", ID: id})
	return nil
}
func (t *transaction) DeleteTransaction(id string) error {
	if _, e := t.Transaction(id); e != nil {
		return e
	}
	for i, v := range t.state.Transactions {
		if v.ID == id {
			t.state.Transactions = append(t.state.Transactions[:i], t.state.Transactions[i+1:]...)
			break
		}
	}
	t.changes = append(t.changes, change{Kind: "deleteTransaction", TransactionID: id})
	return nil
}
