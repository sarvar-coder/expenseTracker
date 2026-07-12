import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../features/activity/activity_screen.dart';
import '../features/add/add_screen.dart';
import '../features/home/home_screen.dart';
import '../features/insights/insights_screen.dart';
import '../features/settings/settings_screen.dart';
import '../providers/providers.dart';
import 'theme.dart';

/// Bottom-nav shell: 4 tabs in an IndexedStack + a center FAB that pushes the
/// Add screen as a full route (matches the mockup). Tab index lives in
/// [tabIndexProvider] so other screens can switch tabs.
class AppShell extends ConsumerWidget {
  const AppShell({super.key});

  static const _pages = [
    HomeScreen(),
    ActivityScreen(),
    InsightsScreen(),
    SettingsScreen(),
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final index = ref.watch(tabIndexProvider);
    return Scaffold(
      body: SafeArea(bottom: false, child: IndexedStack(index: index, children: _pages)),
      bottomNavigationBar: _NavBar(
        index: index,
        onTap: (i) => ref.read(tabIndexProvider.notifier).set(i),
        onAdd: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const AddScreen(), fullscreenDialog: true),
        ),
      ),
    );
  }
}

class _NavBar extends StatelessWidget {
  const _NavBar({required this.index, required this.onTap, required this.onAdd});

  final int index;
  final ValueChanged<int> onTap;
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 70 + MediaQuery.of(context).padding.bottom,
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).padding.bottom),
      decoration: const BoxDecoration(
        color: AppColors.card,
        border: Border(top: BorderSide(color: AppColors.border)),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          _NavItem(icon: Icons.home_outlined, label: 'Asosiy', selected: index == 0, onTap: () => onTap(0)),
          _NavItem(icon: Icons.receipt_long_outlined, label: 'Tarix', selected: index == 1, onTap: () => onTap(1)),
          _Fab(onTap: onAdd),
          _NavItem(icon: Icons.donut_large_outlined, label: 'Tahlil', selected: index == 2, onTap: () => onTap(2)),
          _NavItem(icon: Icons.settings_outlined, label: 'Sozlamalar', selected: index == 3, onTap: () => onTap(3)),
        ],
      ),
    );
  }
}

class _NavItem extends StatelessWidget {
  const _NavItem({required this.icon, required this.label, required this.selected, required this.onTap});

  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected ? AppColors.accent : const Color(0xFFB0ACA2);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: SizedBox(
        width: 56,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, color: color, size: 24),
            const SizedBox(height: 3),
            Text(label, style: TextStyle(color: color, fontSize: 10)),
          ],
        ),
      ),
    );
  }
}

class _Fab extends StatelessWidget {
  const _Fab({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Transform.translate(
      offset: const Offset(0, -12),
      child: Material(
        color: AppColors.accent,
        borderRadius: BorderRadius.circular(18),
        elevation: 6,
        shadowColor: AppColors.accent.withValues(alpha: 0.4),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(18),
          child: const SizedBox(
            width: 56,
            height: 56,
            child: Icon(Icons.add, color: Colors.white, size: 26),
          ),
        ),
      ),
    );
  }
}
