import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../features/activity/activity_screen.dart';
import '../features/add/add_screen.dart';
import '../features/family/family_screen.dart';
import '../features/home/home_screen.dart';
import '../features/insights/insights_screen.dart';
import '../providers/providers.dart';
import 'theme.dart';

/// Bottom-nav shell: 4 tabs in an IndexedStack + a bottom-right FAB that
/// pushes the Add screen as a full route. Settings opens from the Home header. Tab index
/// lives in [tabIndexProvider] so other screens can switch tabs.
class AppShell extends ConsumerWidget {
  const AppShell({super.key});

  static const _pages = [
    HomeScreen(),
    ActivityScreen(),
    InsightsScreen(),
    FamilyScreen(),
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final index = ref.watch(tabIndexProvider);
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: IndexedStack(index: index, children: _pages),
      ),
      floatingActionButton: _Fab(
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => const AddScreen(),
            fullscreenDialog: true,
          ),
        ),
      ),
      bottomNavigationBar: _NavBar(
        index: index,
        onTap: (i) => ref.read(tabIndexProvider.notifier).set(i),
      ),
    );
  }
}

class _NavBar extends StatelessWidget {
  const _NavBar({required this.index, required this.onTap});

  final int index;
  final ValueChanged<int> onTap;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: c.card,
        borderRadius: const BorderRadius.vertical(
          top: Radius.circular(AppRadii.card),
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.06),
            blurRadius: 24,
            offset: const Offset(0, -4),
          ),
        ],
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
          child: SizedBox(
            height: 60,
            child: Row(
              children: [
                _NavItem(
                  icon: Icons.home_outlined,
                  selectedIcon: Icons.home_rounded,
                  label: 'Asosiy',
                  selected: index == 0,
                  onTap: () => onTap(0),
                ),
                _NavItem(
                  icon: Icons.receipt_long_outlined,
                  selectedIcon: Icons.receipt_long_rounded,
                  label: 'Tarix',
                  selected: index == 1,
                  onTap: () => onTap(1),
                ),
                _NavItem(
                  icon: Icons.donut_large_outlined,
                  selectedIcon: Icons.donut_large_rounded,
                  label: 'Tahlil',
                  selected: index == 2,
                  onTap: () => onTap(2),
                ),
                _NavItem(
                  icon: Icons.family_restroom_outlined,
                  selectedIcon: Icons.family_restroom_rounded,
                  label: 'Oila',
                  selected: index == FamilyScreen.tab,
                  onTap: () => onTap(FamilyScreen.tab),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Tab: filled icon in a tinted pill when selected, outlined otherwise.
class _NavItem extends StatelessWidget {
  const _NavItem({
    required this.icon,
    required this.selectedIcon,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final IconData icon, selectedIcon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final color = selected ? c.accent : c.navInactive;
    final style = Theme.of(context).textTheme.labelSmall!.copyWith(
      color: color,
      fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
    );
    return Expanded(
      child: Semantics(
        button: true,
        selected: selected,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(AppRadii.md),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              AnimatedContainer(
                duration: const Duration(milliseconds: 220),
                curve: Curves.easeOutCubic,
                width: selected ? 56 : 40,
                height: 30,
                decoration: BoxDecoration(
                  color: selected
                      ? c.accent.withValues(alpha: 0.14)
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(AppRadii.chip),
                ),
                child: Icon(
                  selected ? selectedIcon : icon,
                  color: color,
                  size: 22,
                ),
              ),
              const SizedBox(height: 4),
              Text(label, style: style),
            ],
          ),
        ),
      ),
    );
  }
}

/// Floating add button: chunky accent square, bottom-right above the bar.
class _Fab extends StatelessWidget {
  const _Fab({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(AppRadii.card),
        boxShadow: [
          BoxShadow(
            color: c.accent.withValues(alpha: 0.35),
            blurRadius: 18,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Material(
        color: c.accent,
        borderRadius: BorderRadius.circular(AppRadii.card),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(AppRadii.card),
          child: SizedBox(
            width: 64,
            height: 64,
            child: Icon(
              Icons.add,
              color: c.onAccent,
              size: 30,
              semanticLabel: 'Xarajat qo\'shish',
            ),
          ),
        ),
      ),
    );
  }
}
