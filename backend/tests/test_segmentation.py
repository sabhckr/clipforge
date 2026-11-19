"""Pure-function tests for the hybrid segmentation rules."""
import pytest

from clipforge.segmentation import SegmentParams, clean_scene_times, plan_segments

P = SegmentParams()  # 30 s target, ±5 s window, 15 s min, 35 s max, 10 s remainder


def bounds(plan):
    return [(s.start, s.end) for s in plan.segments]


def assert_invariants(plan, duration, overlap=0.0):
    segs = plan.segments
    assert segs, "no segments"
    assert segs[0].start == 0
    for s in segs:
        assert s.end > s.start, "zero-length clip"
        assert s.end <= duration + 1e-9, "clip exceeds source"
    for a, b in zip(segs, segs[1:]):
        if overlap == 0:
            assert b.start >= a.end - 1e-9, "overlapping clips"
        assert b.end > a.end, "duplicate boundary"
    assert len({s.end for s in segs}) == len(segs)


def test_fixed_interval_exact_multiple():
    plan = plan_segments(90, [], P, method="fixed")
    assert bounds(plan) == [(0, 30), (30, 60), (60, 90)]
    assert all(s.end_kind in ("fixed", "end") for s in plan.segments)
    assert_invariants(plan, 90)


def test_hybrid_snaps_to_nearest_scene_in_window():
    plan = plan_segments(90, [27.5, 33.9, 62.0, 70.0], P)
    assert bounds(plan) == [(0, 27.5), (27.5, 62.0), (62.0, 90)]
    assert plan.segments[0].end_kind == "scene"
    assert plan.scene_boundaries_used == 2
    assert_invariants(plan, 90)


def test_hybrid_ignores_scenes_outside_window():
    # 20 s is outside [25, 35]; 40 s is outside first window
    plan = plan_segments(70, [20.0, 40.0], P)
    assert plan.segments[0].end == 30
    assert plan.segments[0].end_kind == "fixed"
    assert_invariants(plan, 70)


def test_tie_breaks_to_earlier_boundary():
    plan = plan_segments(80, [28.0, 32.0], P)
    assert plan.segments[0].end == 28.0


def test_min_duration_respected_by_window():
    p = SegmentParams(target=30, window=20, min_duration=15, max_duration=50)
    plan = plan_segments(100, [5.0, 12.0, 16.0], p)
    assert plan.segments[0].end == 16.0  # 5 and 12 violate the 15 s minimum
    assert all(s.duration >= 15 for s in plan.segments)


def test_short_remainder_avoided_when_possible():
    # 68 s: a naive 30/30/8 split would leave an 8 s tail; the planner shifts the cut.
    plan = plan_segments(68, [], P, method="fixed")
    assert all(s.duration >= P.remainder_min for s in plan.segments)
    assert plan.segments[-1].end == 68
    assert plan.dropped_tail == 0
    assert_invariants(plan, 68)


def test_short_remainder_dropped_with_note_when_unavoidable():
    p = SegmentParams(target=30, window=0, min_duration=30, max_duration=30,
                      remainder_min=10, short_remainder="drop")
    plan = plan_segments(66, [], p, method="fixed")
    assert bounds(plan) == [(0, 30), (30, 60)]
    assert plan.dropped_tail == 6
    assert any("not rendered" in n for n in plan.notes)


def test_short_remainder_merge_policy():
    p = SegmentParams(target=30, window=0, min_duration=30, max_duration=30,
                      remainder_min=10, short_remainder="merge")
    plan = plan_segments(66, [], p, method="fixed")
    assert bounds(plan) == [(0, 30), (30, 66)]
    assert any("merged" in n for n in plan.notes)


def test_long_final_remainder_kept():
    p = SegmentParams(target=30, window=0, min_duration=30, max_duration=30, remainder_min=10)
    plan = plan_segments(78, [], p, method="fixed")
    assert bounds(plan) == [(0, 30), (30, 60), (60, 78)]
    assert plan.segments[-1].end_kind == "end"
    assert plan.dropped_tail == 0


def test_short_video_single_clip():
    plan = plan_segments(22, [11.0], P)
    assert bounds(plan) == [(0, 22)]


def test_video_shorter_than_remainder_min_rejected():
    with pytest.raises(ValueError):
        plan_segments(4, [], P)


def test_overlap_only_when_configured():
    p = SegmentParams(overlap=2.0)
    plan = plan_segments(90, [], p, method="fixed")
    assert bounds(plan) == [(0, 30), (28, 60), (58, 90)]
    assert_invariants(plan, 90, overlap=2.0)


def test_scene_cleaning_dedupes_and_drops_edges():
    assert clean_scene_times([0, 10.0001, 10.0, 5, 90, 95], 90) == [5.0, 10.0]


def test_duplicate_and_unsorted_scenes_do_not_create_duplicate_boundaries():
    plan = plan_segments(120, [31, 29, 29, 29.0004, 61, 61, 91], P)
    assert_invariants(plan, 120)


@pytest.mark.parametrize("duration", [10, 15.5, 34.9, 35, 35.1, 44.99, 59, 61, 89.3, 3600.7])
@pytest.mark.parametrize("scenes", [[], [7.3, 29.1, 31.7, 64.4, 66.0, 300.2, 1799.9]])
def test_invariants_hold_across_durations(duration, scenes):
    plan = plan_segments(duration, scenes, P)
    assert_invariants(plan, duration)
    covered = plan.segments[-1].end + plan.dropped_tail
    assert abs(covered - round(duration, 3)) < 1e-6  # nothing silently discarded
    for s in plan.segments[:-1]:
        assert P.min_duration - 1e-6 <= s.duration <= P.max_duration + 1e-6
