#!/usr/bin/env python3
"""
Benchmark script for visibility graph performance.
Measures build_visibility_graph() before/after optimizations.

Usage:
    python3 benchmark_visibility_graph.py --task-id <UUID> --iterations 5
    
Requirements:
    - psycopg2-binary
    - pandas (optional, for CSV export)
"""

import argparse
import json
import logging
import statistics
import sys
import time
from datetime import datetime
from typing import Dict, List, Optional, Tuple

try:
    import psycopg2
    from psycopg2.extras import RealDictCursor
except ImportError:
    print("ERROR: psycopg2 not installed. Run: pip install psycopg2-binary")
    sys.exit(1)

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s: %(message)s'
)
log = logging.getLogger(__name__)


class VisibilityGraphBenchmark:
    """Benchmark visibility graph construction."""
    
    def __init__(self, db_url: str):
        self.db_url = db_url
        self.conn = None
        
    def connect(self):
        """Establish database connection."""
        self.conn = psycopg2.connect(self.db_url)
        log.info("Connected to database")
        
    def close(self):
        """Close database connection."""
        if self.conn:
            self.conn.close()
            log.info("Database connection closed")
            
    def get_cluster_count(self, task_id: str) -> int:
        """Get number of OKS clusters for task."""
        with self.conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("""
                SELECT COUNT(DISTINCT cid) AS cluster_count
                FROM (
                    SELECT feature_id,
                           ST_ClusterDBSCAN(geom_utm, eps := 150, minpoints := 1) OVER () AS cid
                    FROM input_feature
                    WHERE task_id = %s::uuid
                      AND object_type = 'oks_connection_point'
                      AND geom_utm IS NOT NULL
                ) t
            """, (task_id,))
            result = cur.fetchone()
            return result['cluster_count'] if result else 0
            
    def get_vertex_stats(self, task_id: str, cluster_id: int) -> Dict:
        """Get vertex statistics for cluster."""
        with self.conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("""
                SELECT 
                    vertex_type,
                    COUNT(*) AS count,
                    AVG(ST_X(geom)) AS avg_x,
                    AVG(ST_Y(geom)) AS avg_y
                FROM visibility_vertex
                WHERE task_id = %s::uuid AND cluster_id = %s
                GROUP BY vertex_type
            """, (task_id, cluster_id))
            
            stats = {'total': 0}
            for row in cur.fetchall():
                stats[row['vertex_type']] = row['count']
                stats['total'] += row['count']
            return stats
            
    def get_edge_stats(self, task_id: str, cluster_id: int) -> Dict:
        """Get edge statistics for cluster."""
        with self.conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("""
                SELECT 
                    COUNT(*) AS total_edges,
                    AVG(length_m) AS avg_length,
                    MAX(length_m) AS max_length,
                    SUM(CASE WHEN is_special THEN 1 ELSE 0 END) AS special_edges
                FROM visibility_edge
                WHERE task_id = %s::uuid AND cluster_id = %s
            """, (task_id, cluster_id))
            
            result = cur.fetchone()
            return dict(result) if result else {}
            
    def check_connectivity(self, task_id: str, cluster_id: int) -> Dict:
        """Check if all OKS vertices can reach candidates."""
        with self.conn.cursor(cursor_factory=RealDictCursor) as cur:
            # Count OKS vertices
            cur.execute("""
                SELECT COUNT(*) AS oks_count
                FROM visibility_vertex
                WHERE task_id = %s::uuid AND cluster_id = %s AND vertex_type = 'oks'
            """, (task_id, cluster_id))
            oks_count = cur.fetchone()['oks_count']
            
            # Count OKS with paths (have edges)
            cur.execute("""
                SELECT COUNT(DISTINCT source_vertex) AS oks_with_edges
                FROM visibility_edge
                WHERE task_id = %s::uuid AND cluster_id = %s
                  AND source_vertex IN (
                      SELECT id FROM visibility_vertex
                      WHERE vertex_type = 'oks'
                  )
            """, (task_id, cluster_id))
            oks_with_edges = cur.fetchone()['oks_with_edges']
            
            return {
                'oks_total': oks_count,
                'oks_reachable': oks_with_edges,
                'oks_unreachable': oks_count - oks_with_edges,
                'connectivity_pct': (oks_with_edges / oks_count * 100) if oks_count > 0 else 0
            }
            
    def run_build_graph(
        self,
        task_id: str,
        cluster_id: int,
        diameter: int = 100,
        r_max_corner: float = 120.0,
        max_corners: int = 400,
        simplify_tolerance: float = 2.0
    ) -> Tuple[Dict, float]:
        """
        Execute build_visibility_graph and measure time.
        
        Returns:
            Tuple of (result_dict, elapsed_ms)
        """
        start_time = time.perf_counter()
        
        with self.conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("""
                SELECT inserted_vertices, inserted_edges, elapsed_ms
                FROM build_visibility_graph(
                    %s::uuid, %s, %s,
                    %s,  -- p_r_max_corner
                    2500.0,  -- p_r_max_candidate
                    500.0,   -- p_r_max_oks_corner
                    %s,      -- p_max_corners
                    1.0,     -- p_oks_buffer_rough
                    %s       -- p_simplify_tolerance
                )
            """, (task_id, cluster_id, diameter, r_max_corner, max_corners, simplify_tolerance))
            
            result = cur.fetchone()
            self.conn.commit()
            
        elapsed_wall = (time.perf_counter() - start_time) * 1000
        
        return dict(result) if result else {}, elapsed_wall
        
    def benchmark_cluster(
        self,
        task_id: str,
        cluster_id: int,
        iterations: int = 3,
        **kwargs
    ) -> Dict:
        """Run multiple iterations for one cluster."""
        results = []
        
        log.info(f"Benchmarking cluster {cluster_id} ({iterations} iterations)...")
        
        for i in range(iterations):
            result, wall_time = self.run_build_graph(task_id, cluster_id, **kwargs)
            results.append({
                'iteration': i + 1,
                'db_elapsed_ms': result.get('elapsed_ms', 0),
                'wall_elapsed_ms': wall_time,
                'vertices': result.get('inserted_vertices', 0),
                'edges': result.get('inserted_edges', 0)
            })
            log.info(f"  Iteration {i+1}: {wall_time:.1f}ms DB:{result.get('elapsed_ms', 0)}ms "
                    f"V:{result.get('inserted_vertices', 0)} E:{result.get('inserted_edges', 0)}")
            
        # Calculate statistics
        wall_times = [r['wall_elapsed_ms'] for r in results]
        
        return {
            'cluster_id': cluster_id,
            'iterations': iterations,
            'params': kwargs,
            'vertex_stats': self.get_vertex_stats(task_id, cluster_id),
            'edge_stats': self.get_edge_stats(task_id, cluster_id),
            'connectivity': self.check_connectivity(task_id, cluster_id),
            'timing': {
                'min_ms': min(wall_times),
                'max_ms': max(wall_times),
                'avg_ms': statistics.mean(wall_times),
                'median_ms': statistics.median(wall_times),
                'stdev_ms': statistics.stdev(wall_times) if len(wall_times) > 1 else 0
            },
            'raw_results': results
        }
        
    def compare_configs(
        self,
        task_id: str,
        cluster_id: int,
        configs: List[Dict],
        iterations: int = 3
    ) -> Dict:
        """Compare different configurations."""
        results = {}
        
        for config_name, config_params in configs.items():
            log.info(f"\n{'='*60}")
            log.info(f"Configuration: {config_name}")
            log.info(f"Parameters: {config_params}")
            log.info(f"{'='*60}")
            
            result = self.benchmark_cluster(
                task_id, cluster_id,
                iterations=iterations,
                **config_params
            )
            results[config_name] = result
            
        return results


def main():
    parser = argparse.ArgumentParser(description='Benchmark visibility graph performance')
    parser.add_argument('--db-url', default='postgresql://postgres:postgres@localhost:5432/heattracer',
                        help='Database connection URL')
    parser.add_argument('--task-id', required=True, help='Task UUID to benchmark')
    parser.add_argument('--cluster-id', type=int, default=1, help='Cluster ID to benchmark')
    parser.add_argument('--iterations', type=int, default=3, help='Number of iterations')
    parser.add_argument('--compare', action='store_true', help='Compare V24 vs V25 configs')
    parser.add_argument('--output', help='Output JSON file path')
    
    args = parser.parse_args()
    
    benchmark = VisibilityGraphBenchmark(args.db_url)
    
    try:
        benchmark.connect()
        
        # Get cluster count
        cluster_count = benchmark.get_cluster_count(args.task_id)
        log.info(f"Task {args.task_id} has {cluster_count} clusters")
        
        if args.compare:
            # Compare V24 (old) vs V25 (optimized) configurations
            configs = {
                'V24_default': {
                    'r_max_corner': 200.0,
                    'max_corners': 1500,
                    'simplify_tolerance': 0.0  # No simplification
                },
                'V25_optimized': {
                    'r_max_corner': 120.0,
                    'max_corners': 400,
                    'simplify_tolerance': 2.0
                },
                'V25_aggressive': {
                    'r_max_corner': 80.0,
                    'max_corners': 200,
                    'simplify_tolerance': 3.0
                }
            }
            
            results = benchmark.compare_configs(
                args.task_id,
                args.cluster_id,
                configs,
                iterations=args.iterations
            )
            
            # Print comparison summary
            print("\n" + "="*70)
            print("COMPARISON SUMMARY")
            print("="*70)
            
            baseline = results['V24_default']['timing']['avg_ms']
            
            for config_name, result in results.items():
                avg_time = result['timing']['avg_ms']
                speedup = baseline / avg_time if avg_time > 0 else 0
                vertices = result['vertex_stats']['total']
                edges = result['edge_stats'].get('total_edges', 0)
                connectivity = result['connectivity']['connectivity_pct']
                
                print(f"\n{config_name}:")
                print(f"  Avg time: {avg_time:.1f}ms (speedup: {speedup:.2f}x)")
                print(f"  Vertices: {vertices}, Edges: {edges}")
                print(f"  Connectivity: {connectivity:.1f}%")
                
        else:
            # Single configuration benchmark
            result = benchmark.benchmark_cluster(
                args.task_id,
                args.cluster_id,
                iterations=args.iterations,
                r_max_corner=120.0,
                max_corners=400,
                simplify_tolerance=2.0
            )
            
            print("\n" + "="*70)
            print("BENCHMARK RESULTS")
            print("="*70)
            print(f"Cluster: {result['cluster_id']}")
            print(f"Iterations: {result['iterations']}")
            print(f"\nTiming (wall clock):")
            print(f"  Min: {result['timing']['min_ms']:.1f}ms")
            print(f"  Max: {result['timing']['max_ms']:.1f}ms")
            print(f"  Avg: {result['timing']['avg_ms']:.1f}ms")
            print(f"  Median: {result['timing']['median_ms']:.1f}ms")
            print(f"\nGraph stats:")
            print(f"  Vertices: {result['vertex_stats']['total']}")
            print(f"  Edges: {result['edge_stats'].get('total_edges', 0)}")
            print(f"\nConnectivity:")
            print(f"  OKS reachable: {result['connectivity']['oks_reachable']}/{result['connectivity']['oks_total']} "
                  f"({result['connectivity']['connectivity_pct']:.1f}%)")
            
            results = {'single_config': result}
            
        # Save to JSON if requested
        if args.output:
            with open(args.output, 'w') as f:
                json.dump(results, f, indent=2, default=str)
            log.info(f"Results saved to {args.output}")
            
    except Exception as e:
        log.error(f"Benchmark failed: {e}", exc_info=True)
        sys.exit(1)
    finally:
        benchmark.close()


if __name__ == '__main__':
    main()
