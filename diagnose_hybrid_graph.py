#!/usr/bin/env python3
"""
Диагностика гибридного графа видимости.
Запускается ПОСЛЕ выполнения HybridVisibilityGraphService.buildHybrid().

Проверяет:
1. Сколько рёбер было создано в coarse-графе.
2. Сколько рёбер отбраковал JTS.
3. Есть ли связность у OKS (рёбра к чужим углам или candidate).
"""

import psycopg2
import sys
from typing import Tuple, List, Dict

DB_CONFIG = {
    'host': 'localhost',
    'port': 5433,
    'database': 'heat_db',
    'user': 'heat_user',
    'password': 'heat_password'
}

def get_latest_task_id(conn) -> str:
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM task ORDER BY created_at DESC LIMIT 1")
        row = cur.fetchone()
        if not row:
            raise Exception("No tasks found")
        return row[0]

def check_coarse_graph_stats(conn, task_id: str) -> Dict:
    """Статистика coarse-графа до JTS валидации."""
    with conn.cursor() as cur:
        # Общее количество рёбер
        cur.execute("""
            SELECT count(*) FROM visibility_edge 
            WHERE task_id = %s
        """, (task_id,))
        total_edges = cur.fetchone()[0]
        
        # Рёбра по типам вершин
        cur.execute("""
            SELECT sv.vertex_type AS src_type, tv.vertex_type AS tgt_type, count(*)
            FROM visibility_edge ve
            JOIN visibility_vertex sv ON sv.id = ve.source_vertex
            JOIN visibility_vertex tv ON tv.id = ve.target_vertex
            WHERE ve.task_id = %s
            GROUP BY sv.vertex_type, tv.vertex_type
            ORDER BY count(*) DESC
        """, (task_id,))
        edge_types = cur.fetchall()
        
        return {
            'total_edges': total_edges,
            'edge_types': edge_types
        }

def check_oks_connectivity(conn, task_id: str) -> List[Dict]:
    """Проверка: есть ли у OKS рёбра к чужим углам или candidate."""
    with conn.cursor() as cur:
        cur.execute("""
            SELECT 
                sv.ref_id AS oks_id,
                tv.vertex_type AS target_type,
                tv.ref_id AS target_ref,
                tv.own_polygon_id,
                ve.id AS edge_id,
                ST_Length(ve.geom) AS length_m
            FROM visibility_edge ve
            JOIN visibility_vertex sv ON sv.id = ve.source_vertex
            JOIN visibility_vertex tv ON tv.id = ve.target_vertex
            WHERE ve.task_id = %s
              AND sv.vertex_type = 'oks'
              AND (
                  tv.vertex_type != 'polygon_corner'  -- candidate или другая oks
                  OR tv.own_polygon_id IS NULL        -- чужой угол
                  OR tv.own_polygon_id != sv.own_polygon_id  -- чужой угол (явная проверка)
              )
            ORDER BY sv.ref_id, tv.vertex_type, tv.ref_id
            LIMIT 50
        """, (task_id,))
        
        results = []
        for row in cur.fetchall():
            results.append({
                'oks_id': row[0],
                'target_type': row[1],
                'target_ref': row[2],
                'own_polygon_id': row[3],
                'edge_id': row[4],
                'length_m': float(row[5]) if row[5] else None
            })
        return results

def check_unconnected_oks(conn, task_id: str) -> List[int]:
    """Найти OKS, у которых нет рёбер наружу (только к своим углам)."""
    with conn.cursor() as cur:
        # Все OKS
        cur.execute("""
            SELECT ref_id FROM visibility_vertex 
            WHERE task_id = %s AND vertex_type = 'oks'
        """, (task_id,))
        all_oks = set(row[0] for row in cur.fetchall())
        
        # OKS с рёбрами наружу
        cur.execute("""
            SELECT DISTINCT sv.ref_id
            FROM visibility_edge ve
            JOIN visibility_vertex sv ON sv.id = ve.source_vertex
            JOIN visibility_vertex tv ON tv.id = ve.target_vertex
            WHERE ve.task_id = %s
              AND sv.vertex_type = 'oks'
              AND (
                  tv.vertex_type != 'polygon_corner'
                  OR tv.own_polygon_id IS NULL
                  OR tv.own_polygon_id != sv.own_polygon_id
              )
        """, (task_id,))
        connected_oks = set(row[0] for row in cur.fetchall())
        
        unconnected = sorted(all_oks - connected_oks)
        return unconnected

def main():
    print("=" * 60)
    print("ДИАГНОСТИКА ГИБРИДНОГО ГРАФА ВИДИМОСТИ")
    print("=" * 60)
    
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        print(f"✓ Подключено к БД: {DB_CONFIG['database']}")
    except Exception as e:
        print(f"✗ Ошибка подключения: {e}")
        sys.exit(1)
    
    task_id = get_latest_task_id(conn)
    print(f"✓ Последняя задача: {task_id}")
    print()
    
    # 1. Статистика coarse-графа
    print("1. СТАТИСТИКА COARSE-ГРАФА (до JTS валидации)")
    print("-" * 60)
    stats = check_coarse_graph_stats(conn, task_id)
    print(f"   Всего рёбер: {stats['total_edges']}")
    print(f"   Типы рёбер:")
    for src, tgt, cnt in stats['edge_types']:
        print(f"      {src:15} → {tgt:15}: {cnt}")
    print()
    
    # 2. Проверка связности OKS
    print("2. СВЯЗНОСТЬ OKS (рёбра наружу)")
    print("-" * 60)
    connectivity = check_oks_connectivity(conn, task_id)
    if connectivity:
        print(f"   Найдено {len(connectivity)} рёбер от OKS к чужим вершинам:")
        for item in connectivity[:10]:
            print(f"      OKS {item['oks_id']} → {item['target_type']} {item['target_ref']} "
                  f"(len={item['length_m']:.1f}м, edge_id={item['edge_id']})")
        if len(connectivity) > 10:
            print(f"      ... и ещё {len(connectivity) - 10}")
    else:
        print("   ✗ Нет рёбер от OKS к чужим вершинам!")
    print()
    
    # 3. Неподключённые OKS
    print("3. НЕПОДКЛЮЧЁННЫЕ OKS (только свои углы)")
    print("-" * 60)
    unconnected = check_unconnected_oks(conn, task_id)
    if unconnected:
        print(f"   ✗ {len(unconnected)} OKS без выхода наружу: {unconnected}")
    else:
        print("   ✓ Все OKS имеют выход наружу!")
    print()
    
    # 4. Вывод
    print("=" * 60)
    print("ВЫВОД:")
    if unconnected:
        print(f"   ❌ ГИБРИД НЕ РАБОТАЕТ: {len(unconnected)} OKS изолированы")
        print("   РЕКОМЕНДАЦИЯ: Переходить на полный JTS D1 (waypoints сетки)")
    else:
        print("   ✓ ГИБРИД РАБОТАЕТ: все OKS имеют выход наружу")
        print("   СЛЕДУЮЩИЙ ШАГ: Запустить A* поиск путей (D2)")
    print("=" * 60)
    
    conn.close()

if __name__ == '__main__':
    main()
