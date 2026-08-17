[CmdletBinding()]
param(
    [ValidateSet("2025")]
    [string]$DatasetVersion = "2025",

    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if (-not ("SoundConnect.LocationSeed.TurkishNaturalStringComparer" -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace SoundConnect.LocationSeed
{
    public sealed class TurkishNaturalStringComparer : IComparer<string>
    {
        private const string TurkishAlphabet = "abc\u00e7defg\u011fh\u0131ijklmno\u00f6prs\u015ftu\u00fcvyz";
        private static readonly CultureInfo Turkish = CultureInfo.GetCultureInfo("tr-TR");

        public int Compare(string left, string right)
        {
            if (ReferenceEquals(left, right)) return 0;
            if (left == null) return -1;
            if (right == null) return 1;

            string normalizedLeft = NormalizeTurkish(left);
            string normalizedRight = NormalizeTurkish(right);

            int leftIndex = 0;
            int rightIndex = 0;
            while (leftIndex < normalizedLeft.Length && rightIndex < normalizedRight.Length)
            {
                bool leftIsDigit = IsAsciiDigit(normalizedLeft[leftIndex]);
                bool rightIsDigit = IsAsciiDigit(normalizedRight[rightIndex]);
                if (leftIsDigit && rightIsDigit)
                {
                    int leftEnd = DigitRunEnd(normalizedLeft, leftIndex);
                    int rightEnd = DigitRunEnd(normalizedRight, rightIndex);
                    int numericComparison = CompareDigitRuns(
                        normalizedLeft, leftIndex, leftEnd,
                        normalizedRight, rightIndex, rightEnd
                    );
                    if (numericComparison != 0) return numericComparison;
                    leftIndex = leftEnd;
                    rightIndex = rightEnd;
                    continue;
                }
                if (leftIsDigit != rightIsDigit) return leftIsDigit ? -1 : 1;

                int weightComparison = TurkishWeight(normalizedLeft[leftIndex])
                    .CompareTo(TurkishWeight(normalizedRight[rightIndex]));
                if (weightComparison != 0) return weightComparison;
                leftIndex++;
                rightIndex++;
            }

            int lengthComparison = normalizedLeft.Length.CompareTo(normalizedRight.Length);
            return lengthComparison != 0
                ? lengthComparison
                : StringComparer.Ordinal.Compare(left, right);
        }

        private static string NormalizeTurkish(string value)
        {
            return value.Normalize(NormalizationForm.FormC)
                .ToLower(Turkish)
                .Replace('\u00e2', 'a')
                .Replace('\u00ee', 'i')
                .Replace('\u00fb', 'u');
        }

        private static int TurkishWeight(char value)
        {
            int alphabetIndex = TurkishAlphabet.IndexOf(value);
            if (alphabetIndex >= 0) return 1000 + alphabetIndex;
            if (value == ' ') return 0;
            return 100 + value;
        }

        private static int CompareDigitRuns(
            string left, int leftStart, int leftEnd,
            string right, int rightStart, int rightEnd)
        {
            int leftSignificantStart = SkipLeadingZeros(left, leftStart, leftEnd);
            int rightSignificantStart = SkipLeadingZeros(right, rightStart, rightEnd);
            int leftSignificantLength = leftEnd - leftSignificantStart;
            int rightSignificantLength = rightEnd - rightSignificantStart;
            int lengthComparison = leftSignificantLength.CompareTo(rightSignificantLength);
            if (lengthComparison != 0) return lengthComparison;

            for (int index = 0; index < leftSignificantLength; index++)
            {
                int digitComparison = left[leftSignificantStart + index]
                    .CompareTo(right[rightSignificantStart + index]);
                if (digitComparison != 0) return digitComparison;
            }
            return (leftEnd - leftStart).CompareTo(rightEnd - rightStart);
        }

        private static int SkipLeadingZeros(string value, int start, int end)
        {
            int index = start;
            while (index < end - 1 && value[index] == '0') index++;
            return index;
        }

        private static int DigitRunEnd(string value, int start)
        {
            int index = start;
            while (index < value.Length && IsAsciiDigit(value[index])) index++;
            return index;
        }

        private static bool IsAsciiDigit(char value)
        {
            return value >= '0' && value <= '9';
        }
    }
}
"@
}
$turkishComparer = [SoundConnect.LocationSeed.TurkishNaturalStringComparer]::new()

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $PSScriptRoot "..\src\main\resources\location-seed.json"
}

$datasetBaseUrl = "https://api.turkiyeapi.dev/v2/datasets/$DatasetVersion"
$expectedCounts = @{
    provinces = 81
    districts = 973
    municipalities = 1377
    neighborhoods = 32254
}

function Read-Dataset {
    param([Parameter(Mandatory = $true)][string]$ResourceName)

    $uri = "$datasetBaseUrl/$ResourceName.json"
    Write-Host "Downloading $uri"
    return @(Invoke-RestMethod -Uri $uri -Method Get)
}

function Assert-Count {
    param(
        [Parameter(Mandatory = $true)][string]$ResourceName,
        [Parameter(Mandatory = $true)][object[]]$Records
    )

    $expected = $expectedCounts[$ResourceName]
    if ($Records.Count -ne $expected) {
        throw "Unexpected $ResourceName count. Expected $expected, received $($Records.Count)."
    }
}

function Assert-DistinctIds {
    param(
        [Parameter(Mandatory = $true)][string]$ResourceName,
        [Parameter(Mandatory = $true)][object[]]$Records
    )

    $distinctCount = @($Records.id | Sort-Object -Unique).Count
    if ($distinctCount -ne $Records.Count) {
        throw "$ResourceName contains duplicate ids."
    }
}

function Sort-TurkishNamedRecords {
    param([Parameter(Mandatory = $true)][object[]]$Records)

    $items = [System.Collections.Generic.List[object]]::new()
    foreach ($record in $Records) {
        $items.Add($record)
    }
    $comparer = $turkishComparer
    $items.Sort([Comparison[object]]{
        param($left, $right)
        return $comparer.Compare([string]$left.name, [string]$right.name)
    }.GetNewClosure())
    return @($items)
}

function Sort-TurkishStrings {
    param([Parameter(Mandatory = $true)][string[]]$Values)

    $items = [System.Collections.Generic.List[string]]::new()
    foreach ($value in $Values) {
        $items.Add($value)
    }
    $items.Sort($turkishComparer)
    return @($items)
}

$provinces = Read-Dataset -ResourceName "provinces"
$districts = Read-Dataset -ResourceName "districts"
$municipalities = Read-Dataset -ResourceName "municipalities"
$neighborhoods = Read-Dataset -ResourceName "neighborhoods"

Assert-Count -ResourceName "provinces" -Records $provinces
Assert-Count -ResourceName "districts" -Records $districts
Assert-Count -ResourceName "municipalities" -Records $municipalities
Assert-Count -ResourceName "neighborhoods" -Records $neighborhoods
Assert-DistinctIds -ResourceName "provinces" -Records $provinces
Assert-DistinctIds -ResourceName "districts" -Records $districts
Assert-DistinctIds -ResourceName "municipalities" -Records $municipalities
Assert-DistinctIds -ResourceName "neighborhoods" -Records $neighborhoods

$provinceById = @{}
foreach ($province in $provinces) {
    $provinceById[[int]$province.id] = $province
}

$districtById = @{}
$districtsByProvinceId = @{}
foreach ($district in $districts) {
    $provinceId = [int]$district.provinceId
    if (-not $provinceById.ContainsKey($provinceId)) {
        throw "District $($district.id) references missing province $provinceId."
    }

    $districtById[[int]$district.id] = $district
    if (-not $districtsByProvinceId.ContainsKey($provinceId)) {
        $districtsByProvinceId[$provinceId] = [System.Collections.Generic.List[object]]::new()
    }
    $districtsByProvinceId[$provinceId].Add($district)
}

$municipalityById = @{}
foreach ($municipality in $municipalities) {
    $municipalityById[[int]$municipality.id] = $municipality
}

$neighborhoodsByDistrictId = @{}
$baseNameCounts = @{}
foreach ($neighborhood in $neighborhoods) {
    $districtId = [int]$neighborhood.districtId
    if (-not $districtById.ContainsKey($districtId)) {
        throw "Neighborhood $($neighborhood.id) references missing district $districtId."
    }

    $district = $districtById[$districtId]
    if ([int]$neighborhood.provinceId -ne [int]$district.provinceId) {
        throw "Neighborhood $($neighborhood.id) has a province/district mismatch."
    }

    $municipalityId = [int]$neighborhood.municipalityId
    if (-not $municipalityById.ContainsKey($municipalityId)) {
        throw "Neighborhood $($neighborhood.id) references missing municipality $municipalityId."
    }

    if (-not $neighborhoodsByDistrictId.ContainsKey($districtId)) {
        $neighborhoodsByDistrictId[$districtId] = [System.Collections.Generic.List[object]]::new()
    }
    $neighborhoodsByDistrictId[$districtId].Add($neighborhood)

    $baseNameKey = "$districtId`n$($neighborhood.name)"
    if (-not $baseNameCounts.ContainsKey($baseNameKey)) {
        $baseNameCounts[$baseNameKey] = 0
    }
    $baseNameCounts[$baseNameKey]++
}

$qualifiedNeighborhoodCount = 0
$seed = [System.Collections.Generic.List[object]]::new()
foreach ($province in (Sort-TurkishNamedRecords -Records $provinces)) {
    $provinceId = [int]$province.id
    $districtSeeds = [System.Collections.Generic.List[object]]::new()

    foreach ($district in (Sort-TurkishNamedRecords -Records @($districtsByProvinceId[$provinceId]))) {
        $districtId = [int]$district.id
        $neighborhoodNames = [System.Collections.Generic.List[string]]::new()

        foreach ($neighborhood in ($neighborhoodsByDistrictId[$districtId] | Sort-Object id)) {
            $baseNameKey = "$districtId`n$($neighborhood.name)"
            $displayName = [string]$neighborhood.name
            if ($baseNameCounts[$baseNameKey] -gt 1) {
                $municipality = $municipalityById[[int]$neighborhood.municipalityId]
                $displayName = "$displayName ($($municipality.name))"
                $qualifiedNeighborhoodCount++
            }
            $neighborhoodNames.Add($displayName)
        }

        $sortedNeighborhoodNames = @(Sort-TurkishStrings -Values @($neighborhoodNames))
        if (@($sortedNeighborhoodNames | Sort-Object -Unique).Count -ne $sortedNeighborhoodNames.Count) {
			throw "Generated duplicate neighborhood labels for $($province.name)/$($district.name)."
		}

		$districtSeeds.Add([ordered]@{
			name = [string]$district.name
			neighborhoods = $sortedNeighborhoodNames
		})
    }

    $seed.Add([ordered]@{
        name = [string]$province.name
        districts = @($districtSeeds)
    })
}

$generatedDistrictCount = ($seed | ForEach-Object { $_.districts.Count } | Measure-Object -Sum).Sum
$generatedNeighborhoodCount = ($seed | ForEach-Object {
    $_.districts | ForEach-Object { $_.neighborhoods.Count }
} | Measure-Object -Sum).Sum

if ($seed.Count -ne $expectedCounts.provinces -or
        $generatedDistrictCount -ne $expectedCounts.districts -or
        $generatedNeighborhoodCount -ne $expectedCounts.neighborhoods) {
    throw "Generated hierarchy counts do not match the source datasets."
}

$fullOutputPath = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = [IO.Path]::GetDirectoryName($fullOutputPath)
if (-not [IO.Directory]::Exists($outputDirectory)) {
    [IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
}

$json = ($seed | ConvertTo-Json -Depth 6).Replace("`r`n", "`n")
$utf8WithoutBom = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($fullOutputPath, "$json`n", $utf8WithoutBom)

Write-Host "Wrote $fullOutputPath"
Write-Host "Cities: $($seed.Count), districts: $generatedDistrictCount, neighborhoods: $generatedNeighborhoodCount"
Write-Host "Municipality-qualified duplicate neighborhood labels: $qualifiedNeighborhoodCount"
