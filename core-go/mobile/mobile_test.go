package mobile

import (
	"encoding/json"
	"errors"
	"testing"
)

type storeStub struct {
	state  snapshot
	batch  []change
	aborts int
	fail   bool
}

func (s *storeStub) Begin(bool) (string, error) {
	data, _ := json.Marshal(s.state)
	return string(data), nil
}
func (s *storeStub) Finish(data string) error {
	if s.fail {
		return errors.New("private failure")
	}
	if e := json.Unmarshal([]byte(data), &s.batch); e != nil {
		return e
	}
	tx := transaction{state: s.state}
	for _, c := range s.batch {
		switch c.Kind {
		case "saveCategory":
			found := false
			for i, v := range tx.state.Categories {
				if v.ID == c.Category.ID {
					tx.state.Categories[i] = *c.Category
					found = true
				}
			}
			if !found {
				tx.state.Categories = append(tx.state.Categories, *c.Category)
			}
		case "saveTransaction":
			found := false
			for i, v := range tx.state.Transactions {
				if v.ID == c.Transaction.ID {
					tx.state.Transactions[i] = *c.Transaction
					found = true
				}
			}
			if !found {
				tx.state.Transactions = append(tx.state.Transactions, *c.Transaction)
			}
		case "deleteCategory":
			_ = tx.DeleteCategory(c.ID)
		case "deleteTransaction":
			_ = tx.DeleteTransaction(c.TransactionID)
		}
	}
	s.state = tx.state
	return nil
}
func (s *storeStub) Abort() error { s.aborts++; return nil }
func call(t *testing.T, s Store, op, payload string) map[string]any {
	t.Helper()
	raw := Execute(s, op, payload, "2026-10-16T12:00:00+07:00")
	var result map[string]any
	if e := json.Unmarshal([]byte(raw), &result); e != nil {
		t.Fatal(e)
	}
	return result
}
func TestBridgeRoundTrip(t *testing.T) {
	s := &storeStub{}
	if r := call(t, s, "seed", "{}"); r["OK"] != true {
		t.Fatal(r)
	}
	if len(s.state.Categories) != 8 {
		t.Fatal(s.state)
	}
	for _, op := range []string{"window", "categories", "listTransactions", "summary", "breakdown", "purge"} {
		if r := call(t, s, op, "{}"); r["OK"] != true {
			t.Fatal(op, r)
		}
	}
	if r := call(t, s, "saveTransaction", `{"Input":{"CategoryID":1,"Type":"income","Amount":1200,"Date":"2026-10-16","Note":"uji"}}`); r["OK"] != true {
		t.Fatal(r)
	}
	if r := call(t, s, "exportCSV", "{}"); r["OK"] != true {
		t.Fatal(r)
	}
	preview := call(t, s, "previewDelete", `{"Scope":{"Kind":"all"}}`)["Data"]
	payload, _ := json.Marshal(map[string]any{"Preview": preview, "Confirmed": true})
	if r := call(t, s, "deleteHistory", string(payload)); r["OK"] != true {
		t.Fatal(r)
	}
	if len(s.state.Transactions) != 0 {
		t.Fatal("delete failed")
	}
	if r := call(t, s, "saveCategory", `{"Name":"Uji","Type":"expense","Active":true}`); r["OK"] != true {
		t.Fatal(r)
	}
	if r := call(t, s, "deleteCategory", `{"ID":1}`); r["OK"] != true {
		t.Fatal(r)
	}
}
func TestBridgeFailures(t *testing.T) {
	s := &storeStub{}
	for _, input := range []struct{ op, body string }{{"unknown", "{}"}, {"seed", "{"}, {"seed", `{"unexpected":1}`}, {"seed", "{} {}"}, {"saveTransaction", "{}"}} {
		if r := call(t, s, input.op, input.body); r["OK"] != false {
			t.Fatal(r)
		}
	}
	if r := call(t, nil, "seed", "{}"); r["OK"] != false {
		t.Fatal(r)
	}
	if raw := Execute(s, "window", "{}", "invalid"); !json.Valid([]byte(raw)) {
		t.Fatal(raw)
	}
	s.fail = true
	if r := call(t, s, "seed", "{}"); r["OK"] != false {
		t.Fatal(r)
	}
	if s.aborts == 0 || len(s.state.Categories) != 0 {
		t.Fatal("rollback missing")
	}
}
