package core

import (
	"strings"
	"testing"
)

func TestAnalystReportAggregates(t *testing.T) {
	s, _, _ := setup(t)
	create(t, s, "income", "2026-09-01", "", 1000)
	create(t, s, "income", "2026-10-01", "", 3000)
	create(t, s, "expense", "2026-10-02", "private", 120)
	create(t, s, "expense", "2026-10-09", "private", 80)
	r, e := s.Report(Filter{From: "2026-10-01", To: "2026-10-14"}, false, false)
	if e != nil {
		t.Fatal(e)
	}
	d := r.Dataset
	if d.Days != 14 || d.NoExpenseDays != 12 || d.IncomeCount != 1 || d.ExpenseCount != 2 {
		t.Fatal(d)
	}
	if len(d.IncomeCategories) != 1 || d.IncomeCategories[0].Amount != 3000 || d.IncomeCategories[0].Percent != "100,0" {
		t.Fatal(d.IncomeCategories)
	}
	if len(d.Weekdays) != 7 || d.Weekdays[4].Amount != 200 || d.Weekdays[4].Average != "100,0" || d.Weekdays[4].Days != 2 {
		t.Fatal(d.Weekdays)
	}
	var sum int64
	for _, w := range d.Weekdays {
		sum += w.Amount
	}
	if sum != d.Summary.Expense {
		t.Fatal("weekday reconciliation")
	}
	if len(d.Changes) != 1 || d.Changes[0].Delta != 200 || d.Changes[0].Percent != "tidak tersedia" {
		t.Fatal(d.Changes)
	}
	if !strings.Contains(d.Executive, "Rp 2.800") || strings.Contains(d.Conclusion, "private") {
		t.Fatal(d.Executive)
	}
}
func TestAnalystIncomeOnlyAndOneDay(t *testing.T) {
	s, _, _ := setup(t)
	create(t, s, "income", "2026-10-16", "", 9999999999999)
	r, e := s.Report(Filter{From: "2026-10-16", To: "2026-10-16"}, false, false)
	if e != nil {
		t.Fatal(e)
	}
	d := r.Dataset
	if d.Days != 1 || d.NoExpenseDays != 1 || d.IncomeCategories[0].Amount != 9999999999999 {
		t.Fatal(d)
	}
	if d.Weekdays[0].Average != "tidak tersedia" {
		t.Fatal(d.Weekdays)
	}
}
