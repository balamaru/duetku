package core

import (
	"bytes"
	"reflect"
	"strings"
	"testing"
)

func TestUUIDAndReportReconciliation(t *testing.T) {
	s, _, _ := setup(t)
	a := create(t, s, "income", "2026-10-01", "", 1000000)
	b := create(t, s, "expense", "2026-10-02", "private note", 250000)
	if !ValidUUID(a.ID) || !ValidUUID(b.ID) || a.ID == b.ID {
		t.Fatal("UUID generation")
	}
	f := Filter{From: "2026-10-01", To: "2026-10-16"}
	report, e := s.Report(f, true, false)
	if e != nil {
		t.Fatal(e)
	}
	sum, _ := s.GetSummary(f)
	var csv bytes.Buffer
	csvSum, _ := s.ExportCSV(f, &csv)
	if report.Dataset.Summary != sum || sum != csvSum || report.Dataset.SavingRatio != "75,0" {
		t.Fatal(report.Dataset)
	}
	for _, v := range report.Dataset.Transactions {
		if v.Note != "" {
			t.Fatal("notes leaked")
		}
	}
	if report.Dataset.Categories[0].Percent != "100,0" {
		t.Fatal(report.Dataset.Categories)
	}
	again, _ := s.Report(f, true, false)
	if !reflect.DeepEqual(report, again) {
		t.Fatal("nondeterministic report")
	}
	if _, e = s.Report(Filter{From: "2020-01-01", To: "2020-02-01"}, false, false); e == nil {
		t.Fatal("empty report allowed")
	}
	edited := b.Input
	edited.Amount = 500000
	saved, e := s.SaveTransaction(b.ID, edited)
	if e != nil || saved.ID != b.ID {
		t.Fatal("UUID changed on edit", e)
	}
	preview, _ := s.PreviewDelete(DeleteScope{Kind: "one", ID: b.ID})
	if n, e := s.DeleteHistory(preview, true); e != nil || n != 1 {
		t.Fatal(n, e)
	}
}
func TestReportRetentionAndBuckets(t *testing.T) {
	s, _, _ := setup(t)
	create(t, s, "income", "2025-10-14", "", 100)
	create(t, s, "expense", "2026-10-16", "", 50)
	for _, tc := range []struct {
		from string
		want int
	}{{"2026-10-01", 16}, {"2026-09-01", 7}, {"2025-10-01", 13}} {
		r, e := s.Report(Filter{From: tc.from, To: "2026-10-16"}, false, false)
		if e != nil {
			t.Fatal(e)
		}
		if len(r.Dataset.Trends) != tc.want {
			t.Fatal(tc, len(r.Dataset.Trends))
		}
		if tc.from == "2025-10-01" && (r.Dataset.Period.Start != "2025-10-14" || r.Dataset.ComparisonAvailable) {
			t.Fatal(r.Dataset)
		}
	}
}
func TestRuleGolden(t *testing.T) {
	d := ReportDataset{Period: Window{"2026-10-01", "2026-10-16"}, RecordedSince: "2026-09-01", Summary: Summary{Income: 500000, Expense: 1000000, Balance: -500000, Count: 10},
		Categories:         []ReportCategory{{CategoryID: 1, Name: "Makanan", Amount: 500000, Percent: "50,0"}, {CategoryID: 2, Name: "Tagihan", Amount: 300000}, {CategoryID: 3, Name: "Hiburan", Amount: 100000}},
		PreviousCategories: []ReportCategory{{CategoryID: 1, Name: "Makanan", Amount: 250000}}, ComparisonAvailable: true, Previous: Summary{Expense: 800000}, PeakDay: "2026-10-12", PeakAmount: 600000, SpikeDays: 2, Weekday: "Sabtu",
		LargeTransactions: []ReportTransaction{{Transaction: Transaction{Input: Input{Amount: 900000, Date: "2026-10-05"}}, Category: "Tagihan"}}}
	result := RuleBasedAnalyzer{}.Analyze(d)
	expected := []string{
		"Makanan menyumbang 50,0% pengeluaran (Rp 500.000) dan menjadi kategori terbesar.",
		"Tiga kategori teratas mencakup 90,0% total pengeluaran.",
		"Perubahan total pengeluaran dibanding periode sebelumnya: 25,0% (Rp 200.000).",
		"Makanan: perubahan 100,0% (Rp 250.000) dibanding periode sebelumnya.",
		"Pengeluaran tertinggi terjadi pada 2026-10-12 (Rp 600.000).",
		"Terdapat 2 hari dengan pengeluaran jauh di atas biasanya.",
		"Transaksi Tagihan Rp 900.000 pada 2026-10-05 jauh di atas biasanya.",
		"Pengeluaran cenderung tertinggi pada hari Sabtu.",
		"Pengeluaran melebihi pemasukan sebesar Rp 500.000.",
	}
	got := []string{}
	for _, n := range result {
		got = append(got, n.Paragraf...)
		if n.Sumber != "rule" {
			t.Fatal(n)
		}
	}
	if !reflect.DeepEqual(got, expected) {
		t.Fatalf("\ngot %q\nwant %q", got, expected)
	}
	d = ReportDataset{Summary: Summary{Expense: 100, Count: 1}, ComparisonAvailable: true}
	got = nil
	for _, n := range (RuleBasedAnalyzer{}).Analyze(d) {
		got = append(got, n.Paragraf...)
	}
	if !reflect.DeepEqual(got, []string{"Pengeluaran periode sebelumnya nol; perubahan nominal Rp 100. Persentase tidak tersedia.", "Data belum cukup untuk analisis pola."}) {
		t.Fatal(got)
	}
	if decimalRatio(1, 0, 100) != "tidak tersedia" || rupiah(-1000) != "Rp -1.000" {
		t.Fatal("format")
	}
}
func TestReportPatternThresholdsAndAppendix(t *testing.T) {
	s, _, _ := setup(t)
	create(t, s, "income", "2026-09-01", "", 10000000)
	for i := 0; i < 5; i++ {
		create(t, s, "expense", "2026-09-02", "", 1000)
	}
	create(t, s, "expense", "2026-09-12", "", 1000000)
	r, e := s.Report(Filter{From: "2026-09-01", To: "2026-09-30"}, true, true)
	if e != nil || r.Dataset.SpikeDays != 1 || len(r.Dataset.LargeTransactions) != 1 || r.Dataset.Weekday != "Sabtu" {
		t.Fatal(r, e)
	}
	for i := 0; i < 2000; i++ {
		create(t, s, "income", "2026-09-01", "", 1)
	}
	r, e = s.Report(Filter{From: "2026-09-01", To: "2026-09-30"}, true, false)
	if e != nil || len(r.Dataset.Transactions) != 2000 || !strings.Contains(strings.Join(r.Dataset.Notes, " "), "2.000") {
		t.Fatal(e, len(r.Dataset.Transactions))
	}
}
