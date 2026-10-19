package core

import (
	"bytes"
	"encoding/csv"
	"errors"
	"fmt"
	"math"
	"reflect"
	"strings"
	"testing"
	"time"
)

type testClock struct{ now time.Time }

func (c *testClock) Now() time.Time { return c.now }

// Memory repository is a test double only. Copy-on-write verifies rollback semantics.
type memory struct {
	cats        map[int64]Category
	rows        map[string]Transaction
	next        int64
	failDelete  bool
	commitError bool
}

func newMemory() *memory                           { return &memory{cats: map[int64]Category{}, rows: map[string]Transaction{}} }
func (m *memory) Read(fn func(Reader) error) error { return fn(m) }
func (m *memory) Write(fn func(Writer) error) error {
	cp := newMemory()
	cp.next = m.next
	cp.failDelete = m.failDelete
	for id, c := range m.cats {
		cp.cats[id] = c
	}
	for id, v := range m.rows {
		cp.rows[id] = v
	}
	if e := fn(cp); e != nil {
		return e
	}
	if m.commitError {
		return errors.New("private storage details")
	}
	m.cats = cp.cats
	m.rows = cp.rows
	m.next = cp.next
	return nil
}
func (m *memory) Categories() ([]Category, error) {
	out := []Category{}
	for _, c := range m.cats {
		out = append(out, c)
	}
	return out, nil
}
func (m *memory) Category(id int64) (Category, error) {
	c, ok := m.cats[id]
	if !ok {
		return c, fail("NOT_FOUND", "id", "Tidak ditemukan.")
	}
	return c, nil
}
func (m *memory) Transactions() ([]Transaction, error) {
	out := []Transaction{}
	for _, v := range m.rows {
		out = append(out, v)
	}
	return out, nil
}
func (m *memory) Transaction(id string) (Transaction, error) {
	v, ok := m.rows[id]
	if !ok {
		return v, fail("NOT_FOUND", "id", "Tidak ditemukan.")
	}
	return v, nil
}
func (m *memory) SaveCategory(c Category) (Category, error) {
	if c.ID == 0 {
		m.next++
		c.ID = m.next
	}
	m.cats[c.ID] = c
	return c, nil
}
func (m *memory) DeleteCategory(id int64) error { delete(m.cats, id); return nil }
func (m *memory) SaveTransaction(v Transaction) (Transaction, error) {
	if v.ID == "" {
		v.ID, _ = NewUUID()
	}
	m.rows[v.ID] = v
	return v, nil
}
func (m *memory) DeleteTransaction(id string) error {
	if m.failDelete {
		return errors.New("disk failure")
	}
	delete(m.rows, id)
	return nil
}
func setup(t *testing.T) (*Service, *memory, *testClock) {
	t.Helper()
	m := newMemory()
	c := &testClock{time.Date(2026, 10, 16, 0, 30, 0, 0, time.FixedZone("WIB", 7*3600))}
	s, e := New(m, c)
	if e != nil {
		t.Fatal(e)
	}
	if e = s.SeedCategories(); e != nil {
		t.Fatal(e)
	}
	return s, m, c
}
func code(t *testing.T, err error, want string) {
	t.Helper()
	var e *Error
	if !errors.As(err, &e) || e.Code != want {
		t.Fatalf("error=%v want=%s", err, want)
	}
}
func create(t *testing.T, s *Service, kind, date, note string, amount int64) Transaction {
	t.Helper()
	cats, e := s.ListCategories()
	if e != nil {
		t.Fatal(e)
	}
	var id int64
	for _, c := range cats {
		if c.Type == kind && c.Active {
			id = c.ID
			break
		}
	}
	v, e := s.SaveTransaction("", Input{id, kind, amount, date, note})
	if e != nil {
		t.Fatal(e)
	}
	return v
}

func TestRetention(t *testing.T) {
	for _, tc := range [][2]string{{"2026-10-16", "2025-10-14"}, {"2026-10-17", "2025-10-15"}, {"2024-02-29", "2023-02-26"}, {"2025-03-01", "2024-02-28"}, {"2026-01-01", "2024-12-30"}, {"2026-04-30", "2025-04-28"}} {
		w, e := Retention(tc[0])
		if e != nil || w.Start != tc[1] || w.End != tc[0] {
			t.Fatalf("%v: %v %v", tc, w, e)
		}
	}
	for _, date := range []string{"2025-02-29", "2026-2-01", "0000-01-01", "0001-10-16", "0002-01-01", "bad"} {
		_, e := Retention(date)
		code(t, e, "INVALID_DATE")
	}
	s, _, c := setup(t)
	w, e := s.RetentionWindow()
	if e != nil || w.End != "2026-10-16" {
		t.Fatal(w, e)
	}
	c.now = c.now.Add(-time.Hour)
	w, _ = s.RetentionWindow()
	if w.End != "2026-10-15" {
		t.Fatal(w)
	}
}
func TestValidation(t *testing.T) {
	w, _ := Retention("2026-10-16")
	cat := Category{ID: 1, Name: "Gaji", Type: "income", Active: true}
	base := Input{1, "income", 1, w.Start, ""}
	for _, tc := range []struct {
		name, want string
		change     func(*Input)
	}{
		{"type", "INVALID_TYPE", func(i *Input) { i.Type = "other" }}, {"zero", "INVALID_AMOUNT", func(i *Input) { i.Amount = 0 }}, {"large", "INVALID_AMOUNT", func(i *Input) { i.Amount = MaxAmount + 1 }}, {"bad date", "INVALID_DATE", func(i *Input) { i.Date = "2026-02-30" }}, {"expired", "OUTSIDE_RETENTION", func(i *Input) { i.Date = "2025-10-13" }}, {"future", "OUTSIDE_RETENTION", func(i *Input) { i.Date = "2026-10-17" }}, {"note", "INVALID_NOTE", func(i *Input) { i.Note = strings.Repeat("界", 256) }}, {"category", "NOT_FOUND", func(i *Input) { i.CategoryID = 2 }}, {"mismatch", "CATEGORY_TYPE_MISMATCH", func(i *Input) { i.Type = "expense" }},
	} {
		t.Run(tc.name, func(t *testing.T) { in := base; tc.change(&in); code(t, ValidateInput(in, cat, w), tc.want) })
	}
	base.Amount = MaxAmount
	base.Note = strings.Repeat("界", 255)
	if e := ValidateInput(base, cat, w); e != nil {
		t.Fatal(e)
	}
	cat.Active = false
	code(t, ValidateInput(base, cat, w), "INACTIVE_CATEGORY")
}
func TestTransactionsAndCategories(t *testing.T) {
	s, m, _ := setup(t)
	if e := s.SeedCategories(); e != nil {
		t.Fatal(e)
	}
	cats, _ := s.ListCategories()
	if len(cats) != 8 {
		t.Fatal(cats)
	}
	v := create(t, s, "income", "2026-10-16", "awal", 200)
	in := v.Input
	in.Amount = 300
	updated, e := s.SaveTransaction(v.ID, in)
	if e != nil || updated.CreatedAt != v.CreatedAt || updated.ID != v.ID {
		t.Fatal(updated, e)
	}
	_, e = s.SaveTransaction("invalid", in)
	code(t, e, "INVALID_ID")
	_, e = s.SaveTransaction("00000000-0000-4000-8000-000000000999", in)
	code(t, e, "NOT_FOUND")
	_, e = s.SaveCategory(0, " gAJI ", "income", true)
	code(t, e, "DUPLICATE_CATEGORY")
	_, e = s.SaveCategory(0, " ", "income", true)
	code(t, e, "INVALID_CATEGORY")
	c, e := s.SaveCategory(0, "Baru", "expense", true)
	if e != nil {
		t.Fatal(e)
	}
	c, e = s.SaveCategory(c.ID, "Nama baru", "expense", false)
	if e != nil || c.Active {
		t.Fatal(c, e)
	}
	_, e = s.SaveCategory(c.ID, "Nama", "income", true)
	code(t, e, "IMMUTABLE_TYPE")
	inactive, e := s.DeleteCategory(v.CategoryID)
	if e != nil || !inactive {
		t.Fatal(inactive, e)
	}
	_, e = s.SaveTransaction("", in)
	code(t, e, "INACTIVE_CATEGORY")
	inactive, e = s.DeleteCategory(c.ID)
	if e != nil || inactive {
		t.Fatal(inactive, e)
	}
	if _, ok := m.cats[c.ID]; ok {
		t.Fatal("category survived")
	}
	m.commitError = true
	_, e = s.SaveCategory(0, "Rollback", "income", true)
	code(t, e, "STORAGE_ERROR")
	if strings.Contains(e.Error(), "private") {
		t.Fatal("leak")
	}
	if len(m.cats) != 8 {
		t.Fatal("commit failure changed state")
	}
}
func TestQueriesCSVAndPurge(t *testing.T) {
	s, m, c := setup(t)
	a := create(t, s, "income", "2025-10-14", "review", 1000)
	create(t, s, "expense", "2026-10-16", " \t=SUM(1,2)\n\"quoted\"", 400)
	// Stale/future records simulate lifecycle not having purged yet and clock rollback.
	m.rows["100"] = Transaction{ID: "100", Input: Input{a.CategoryID, "income", 500, "2025-10-13", "expired"}}
	m.rows["101"] = Transaction{ID: "101", Input: Input{a.CategoryID, "income", 500, "2026-10-17", "future"}}
	summary, e := s.GetSummary(Filter{})
	if e != nil || summary != (Summary{1000, 400, 600, 2}) {
		t.Fatal(summary, e)
	}
	page, e := s.ListTransactions(Filter{Limit: 1})
	if e != nil || page.Total != 2 || len(page.Items) != 1 || page.Items[0].Type != "expense" {
		t.Fatal(page, e)
	}
	page, e = s.ListTransactions(Filter{Offset: 100})
	if e != nil || len(page.Items) != 0 {
		t.Fatal(page, e)
	}
	page, e = s.ListTransactions(Filter{Search: "REVIEW", Type: "income", MinAmount: 900, MaxAmount: 1100, CategoryID: a.CategoryID})
	if e != nil || page.Total != 1 {
		t.Fatal(page, e)
	}
	empty, e := s.GetSummary(Filter{From: "2020-01-01", To: "2020-02-01"})
	if e != nil || empty.Count != 0 {
		t.Fatal(empty, e)
	}
	breakdown, e := s.GetBreakdown(Filter{})
	if e != nil || len(breakdown) != 2 {
		t.Fatal(breakdown, e)
	}
	var out bytes.Buffer
	exported, e := s.ExportCSV(Filter{Limit: 1, Offset: 1}, &out)
	if e != nil || exported != summary {
		t.Fatal(exported, e)
	}
	records, e := csv.NewReader(&out).ReadAll()
	if e != nil || len(records) != 6 || records[1][4] != "' \t=SUM(1,2)\n\"quoted\"" {
		t.Fatal(records, e)
	}
	var none bytes.Buffer
	_, e = s.ExportCSV(Filter{Search: "missing"}, &none)
	code(t, e, "NO_DATA")
	if none.Len() != 0 {
		t.Fatal("empty export wrote content")
	}
	_, e = s.ExportCSV(Filter{}, nil)
	code(t, e, "EXPORT_ERROR")
	_, e = s.ExportCSV(Filter{}, badWriter{})
	code(t, e, "EXPORT_ERROR")
	purged, e := s.PurgeExpiredHistory()
	if e != nil || purged != 1 {
		t.Fatal(purged, e)
	}
	if _, ok := m.rows["101"]; !ok {
		t.Fatal("future data purged")
	}
	c.now = c.now.AddDate(0, 0, 1)
	purged, e = s.PurgeExpiredHistory()
	if e != nil || purged != 1 {
		t.Fatal(purged, e)
	}
	_, e = s.SaveTransaction(a.ID, a.Input)
	code(t, e, "NOT_FOUND")
}

type badWriter struct{}

func (badWriter) Write([]byte) (int, error) { return 0, errors.New("disk full") }
func TestDelete(t *testing.T) {
	s, m, c := setup(t)
	v := create(t, s, "income", "2026-10-16", "", 100)
	create(t, s, "expense", "2026-10-15", "", 20)
	p, e := s.PreviewDelete(DeleteScope{Kind: "one", ID: v.ID})
	if e != nil || p.Count != 1 {
		t.Fatal(p, e)
	}
	_, e = s.DeleteHistory(p, false)
	code(t, e, "CONFIRMATION_REQUIRED")
	m.failDelete = true
	_, e = s.DeleteHistory(p, true)
	code(t, e, "STORAGE_ERROR")
	if len(m.rows) != 2 {
		t.Fatal("rollback failed")
	}
	m.failDelete = false
	n, e := s.DeleteHistory(p, true)
	if e != nil || n != 1 {
		t.Fatal(n, e)
	}
	_, e = s.DeleteHistory(p, true)
	code(t, e, "STALE_PREVIEW")
	p, e = s.PreviewDelete(DeleteScope{Kind: "range", From: "2026-10-15", To: "2026-10-15"})
	if e != nil || p.Count != 1 {
		t.Fatal(p, e)
	}
	n, e = s.DeleteHistory(p, true)
	if e != nil || n != 1 {
		t.Fatal(n, e)
	}
	create(t, s, "income", "2026-10-16", "", 100)
	p, _ = s.PreviewDelete(DeleteScope{Kind: "all"})
	c.now = c.now.AddDate(0, 0, 1)
	_, e = s.DeleteHistory(p, true)
	code(t, e, "STALE_PREVIEW")
	p, _ = s.PreviewDelete(DeleteScope{Kind: "all"})
	n, e = s.DeleteHistory(p, true)
	if e != nil || n != 1 || len(m.rows) != 0 || len(m.cats) != 8 {
		t.Fatal(n, e)
	}
	for _, scope := range []DeleteScope{{Kind: "bad"}, {Kind: "one"}, {Kind: "range", From: "bad", To: "2026-01-01"}, {Kind: "range", From: "2026-01-01", To: "bad"}, {Kind: "all", ID: "1"}} {
		if _, e = s.PreviewDelete(scope); e == nil {
			t.Fatal(scope)
		}
	}
}
func TestFilterOverflowAndSafety(t *testing.T) {
	s, _, _ := setup(t)
	for _, f := range []Filter{{From: "bad"}, {To: "bad"}, {From: "2026-10-16", To: "2026-10-15"}, {Limit: 101}, {Offset: -1}, {Type: "bad"}, {MinAmount: 2, MaxAmount: 1}, {Search: string([]byte{255})}} {
		if _, e := s.ListTransactions(f); e == nil {
			t.Fatal(f)
		}
	}
	_, e := add(math.MaxInt64, 1)
	code(t, e, "AMOUNT_OVERFLOW")
	_, e = summarize([]Transaction{{Input: Input{Type: "bad", Amount: 1}}})
	code(t, e, "INVALID_DATA")
	_, e = New(nil, &testClock{})
	code(t, e, "INVALID_CONFIGURATION")
	for _, s := range []string{"=x", " +x", "\t-x", "\ufeff@x"} {
		if !strings.HasPrefix(spreadsheetText(s), "'") {
			t.Fatal(s)
		}
	}
	if spreadsheetText("catatan") != "catatan" {
		t.Fatal("plain text changed")
	}
	if !reflect.DeepEqual(selectRows(nil, Filter{}), []Transaction{}) {
		t.Fatal("empty results")
	}
}
func TestTenThousandRows(t *testing.T) {
	s, m, _ := setup(t)
	cats, _ := s.ListCategories()
	for i := int64(1); i <= 10000; i++ {
		id := fmt.Sprintf("00000000-0000-4000-8000-%012d", i)
		m.rows[id] = Transaction{ID: id, Input: Input{cats[0].ID, "income", 100, "2026-10-16", ""}}
	}
	sum, e := s.GetSummary(Filter{})
	if e != nil || sum.Income != 1000000 || sum.Count != 10000 {
		t.Fatal(sum, e)
	}
	page, e := s.ListTransactions(Filter{Offset: 9980})
	if e != nil || len(page.Items) != 20 || page.Items[0].ID != "00000000-0000-4000-8000-000000000020" {
		t.Fatal(page, e)
	}
}

func TestExpiredEditAndAtomicPurge(t *testing.T) {
	s, m, _ := setup(t)
	v := create(t, s, "income", "2025-10-14", "", 1)
	old := v
	old.Date = "2025-10-13"
	m.rows[v.ID] = old
	_, e := s.SaveTransaction(v.ID, v.Input)
	code(t, e, "OUTSIDE_RETENTION")
	m.commitError = true
	n, e := s.PurgeExpiredHistory()
	code(t, e, "STORAGE_ERROR")
	if n != 0 || len(m.rows) != 1 {
		t.Fatal("failed purge reported success")
	}
	m.commitError = false
	n, e = s.PurgeExpiredHistory()
	if e != nil || n != 1 {
		t.Fatal(n, e)
	}
	// A successful callback followed by a failed commit must expose no result.
	m.commitError = true
	result, e := s.SaveTransaction("", v.Input)
	code(t, e, "STORAGE_ERROR")
	if result.ID != "" || len(m.rows) != 0 {
		t.Fatal("failed create leaked result")
	}
	_, e = s.DeleteCategory(999)
	code(t, e, "NOT_FOUND")
	_, e = s.SaveCategory(999, "Unknown", "income", true)
	code(t, e, "NOT_FOUND")
}
