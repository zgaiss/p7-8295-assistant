package update

import "testing"

func TestRegistryBoundaries(t *testing.T) {
	for _, p := range Components() {
		if p.ProductionActivation {
			t.Fatal("activation enabled", p)
		}
	}
	for _, id := range []string{"probe-bsd", "probe-voltage"} {
		p, ok := Component(id)
		if !ok || !p.Privileged || p.Class != ControlledProcess {
			t.Fatal(p)
		}
	}
	for _, id := range []string{"apk", "boot", "watchdog", "control"} {
		m := sample()
		m.Artifacts[0].Component = id
		if _, e := Plan(verified(t, m), compatible()); e == nil {
			t.Fatal("bootstrap in hot plan", id)
		}
	}
	m := sample()
	a := m.Artifacts[0]
	a.Component = "probe-bsd"
	m.Artifacts = append(m.Artifacts, a)
	if _, e := Plan(verified(t, m), compatible()); e == nil {
		t.Fatal("distributed atomicity claimed")
	}
	if _, ok := Component("../../script"); ok {
		t.Fatal("arbitrary path")
	}
}

func TestUnimplementedAndWrongReceiverTarget(t *testing.T) {
	m := sample()
	d := compatible()
	d.Target = "android-shell"
	if _, e := Plan(verified(t, m), d); e == nil {
		t.Fatal("wrong receiver")
	}
	m.Artifacts[0].Component = "probe-bsd"
	if _, e := Plan(verified(t, m), d); e == nil {
		t.Fatal("unimplemented Android compatibility")
	}
}
