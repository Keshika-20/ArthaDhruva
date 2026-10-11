# Segment correlation distance experiment

This experiment documents how the staff's Pearson-distance formula

D_ij = sqrt(2 * (1 - r_ij))

applies to the existing segment-correlation graph analysis.

## What this experiment checks

- Whether the original `data/docs/segment_monthly_series.parquet` artifact exists.
- Whether the existing graph JSON is the only available baseline, or whether the underlying state-month series can be reconstructed from the project data.
- Whether the 15-state correlation matrix reproduces the same edge set at the existing threshold `r >= 0.95`.
- Whether the equivalent distance cutoff preserves the same state pairs.

## Inputs actually used

This experiment uses the repository's actual data sources when available:

- `backend/risk-engine/src/main/resources/segment_correlation_graph.json`
- `data/processed/loan_level/.../*.parquet`
- `data/processed/monthly_panel/.../*.parquet`

The original `data/docs/segment_monthly_series.parquet` file was not present in this checkout, so the notebook reconstructs the state-by-month delinquency series from the processed loan-level and monthly-panel files instead of assuming the missing artifact exists.

## Notebook

The executable notebook is:

`experiments/segment_correlation_distance/pearson_correlation_distance.ipynb`

It performs the inspection, reconstructs the monthly series, recomputes the Pearson matrix, applies the distance formula, saves the derived tables and charts, and verifies the graph threshold equivalence.

## Outputs

The notebook writes all generated artifacts under:

`experiments/segment_correlation_distance/results/`

including:

- `distance_matrix.csv`
- `pearson_correlation_matrix.csv`
- `pairwise_correlation_distance.csv`
- `validation_report.json`
- `pearson_correlation_heatmap.png`
- `distance_matrix_heatmap.png`
- `correlation_vs_distance.png`
- `distance_distribution.png`
- `threshold_equivalence.png`

## How to execute

From the repository root:

```bash
jupyter nbconvert --to notebook --execute experiments/segment_correlation_distance/pearson_correlation_distance.ipynb --inplace
```

## Required libraries

- pandas
- numpy
- matplotlib
- seaborn
- pyarrow

## Notes

The notebook explicitly distinguishes between:

- a complete historical series that can be recomputed from the project data,
- a complete Pearson correlation matrix, and
- a thresholded graph that is insufficient to recover the full distance matrix.

This experiment does not modify any tracked project files outside the new experiment folder.